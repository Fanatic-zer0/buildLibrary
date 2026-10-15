import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

/** Global variable backing Build.<method>() calls in Jenkinsfiles. */
def initEnv() {
  Map settings = loadSettings()
  requireSettings(settings, ['serviceName', 'imageRepository', 'helmChart'])

  String gitSha = sh(script: 'git rev-parse --short=12 HEAD', returnStdout: true).trim()
  String branch = env.BRANCH_NAME ?: 'detached'
  String version = versionFor(settings, gitSha, branch)

  env.SERVICE_NAME = settings.serviceName
  env.imageName = settings.imageRepository
  env.ms_version = version
  env.GIT_SHA = gitSha
  env.namespace = env.CHANGE_ID ? "${settings.preview.namespacePrefix}-${env.CHANGE_ID}" : settings.environments.devNamespace
  env.Build_SETTINGS = JsonOutput.toJson(settings)
  echo "Initialized ${env.SERVICE_NAME}:${env.ms_version}"
}

def mavenTest() { runCommand('maven', 'Maven tests', settings().commands.mavenTest, settings().timeouts.testMinutes) }
def qualityGate() { runCommand('maven', 'Quality gate', settings().commands.qualityGate, settings().timeouts.qualityMinutes) }
def runIntTests() {
  Map config = settings()
  withEnv(["TARGET_NAMESPACE=${env.namespace}", "SERVICE_VERSION=${env.ms_version}"]) {
    runCommand('maven', 'Integration tests', config.commands.integrationTest, config.timeouts.integrationMinutes)
  }
}

def imageBuild() {
  Map config = settings()
  runCommand('builder', 'Build and publish image', """${config.commands.imageBuild} \\
    --tag '${env.imageName}:${env.ms_version}' \\
    --build-arg VERSION='${env.ms_version}' \\
    --build-arg GIT_SHA='${env.GIT_SHA}'""", config.timeouts.buildMinutes)
}

def packageHelmChart() {
  Map config = settings()
  inContainer(config, 'helm') {
    sh "helm lint '${config.helmChart}'"
    sh """mkdir -p dist
      helm package '${config.helmChart}' --version '${env.ms_version}' \\
        --app-version '${env.ms_version}' --destination dist"""
    archiveArtifacts artifacts: 'dist/*.tgz', fingerprint: true
  }
}

def isNamespace(String namespace) {
  withCluster(settings()) {
    sh(script: "kubectl get namespace '${namespace}' >/dev/null 2>&1", returnStatus: true) == 0
  }
}

def createTestNamespace() {
  Map config = settings()
  withCluster(config) {
    sh """kubectl create namespace '${env.namespace}' --dry-run=client -o yaml | kubectl apply -f -
      kubectl label namespace '${env.namespace}' app.kubernetes.io/managed-by=jenkins --overwrite"""
  }
}

def landscape() { deployOptionalChart(settings().preview.landscapeChart, settings().preview.landscapeRelease) }
def installBaseline() { deployOptionalChart(settings().preview.baselineChart, settings().preview.baselineRelease) }

def installTestBuild() {
  Map config = settings()
  deployChart(config.helmChart, env.SERVICE_NAME, "--set image.repository='${env.imageName}' --set image.tag='${env.ms_version}' --set app.version='${env.ms_version}'")
}

def shouldDoTeardown() { return env.CHANGE_ID && currentBuild.currentResult != 'ABORTED' }
def teardownNamespace() {
  withCluster(settings()) {
    sh "kubectl delete namespace '${env.namespace}' --ignore-not-found=true --wait=false"
  }
}

def sonarQubeScan() {
  Map config = settings()
  inContainer(config, 'maven') {
    withSonarQubeEnv(config.sonarQube.server) {
      timeout(time: config.timeouts.qualityMinutes as int, unit: 'MINUTES') {
        sh(label: 'SonarQube analysis', script: "#!/usr/bin/env bash\nset -euo pipefail\n${config.commands.sonarQubeScan}")
      }
    }
    timeout(time: config.timeouts.qualityMinutes as int, unit: 'MINUTES') {
      waitForQualityGate abortPipeline: true
    }
  }
}

def notifyOnFailure() {
  String credential = settings().notifications.webhookCredential
  if (!credential) {
    echo "Build failed: ${env.JOB_NAME} #${env.BUILD_NUMBER}"
    return
  }
  withCredentials([string(credentialsId: credential, variable: 'WEBHOOK_URL')]) {
    sh '''curl --fail --silent --show-error --request POST \
      --header 'Content-Type: application/json' \
      --data "{\"text\":\"Jenkins failed: ${JOB_NAME} #${BUILD_NUMBER} ${BUILD_URL}\"}" \
      "$WEBHOOK_URL"'''
  }
}

private Map loadSettings() {
  if (!fileExists('.opt/jenkins/service.yaml')) {
    error 'Missing .opt/jenkins/service.yaml required by Build.initEnv().'
  }
  return merge(defaultSettings(), readYaml(file: '.opt/jenkins/service.yaml') ?: [:])
}

private Map settings() {
  if (!env.Build_SETTINGS) {
    error 'Run Build.initEnv() before calling other Build methods.'
  }
  return new JsonSlurperClassic().parseText(env.Build_SETTINGS) as Map
}

private void runCommand(String containerName, String label, String command, int minutes) {
  inContainer(settings(), containerName) {
    timeout(time: minutes, unit: 'MINUTES') {
      sh(label: label, script: "#!/usr/bin/env bash\nset -euo pipefail\n${command}")
    }
  }
}

private void deployOptionalChart(String chart, String release) {
  if (!chart) {
    echo 'No optional preview chart configured; skipping.'
    return
  }
  deployChart(chart, release, '')
}

private void deployChart(String chart, String release, String values) {
  Map config = settings()
  withCluster(config) {
    sh "helm upgrade --install '${release}' '${chart}' --namespace '${env.namespace}' --create-namespace --wait --timeout 10m ${values}"
  }
}

private void withCluster(Map config, Closure body) {
  inContainer(config, 'kubectl-helm-aws') {
    withKubeConfig([credentialsId: config.credentials.kubeconfig]) { body() }
  }
}

private void inContainer(Map config, String name, Closure body) {
  config.agent.containers.contains(name) ? container(name) { body() } : body()
}

private String versionFor(Map config, String gitSha, String branch) {
  String tag = sh(script: "git tag --points-at HEAD | grep -E '^v[0-9]+\\.[0-9]+\\.[0-9]+$' | head -1 || true", returnStdout: true).trim()
  return tag ? tag.substring(1) : "${config.version.snapshotPrefix}-${branch.replaceAll('[^A-Za-z0-9.-]', '-')}.${gitSha}"
}

private void requireSettings(Map config, List keys) {
  keys.each { key -> if (!config[key]) { error "Build requires '${key}' in .opt/jenkins/service.yaml" } }
}

private Map merge(Map base, Map override) {
  Map result = [:] + base
  override.each { key, value -> result[key] = result[key] instanceof Map && value instanceof Map ? merge(result[key], value) : value }
  return result
}

private Map defaultSettings() {
  [
    version: [snapshotPrefix: '0.0.0'],
    agent: [containers: ['maven', 'builder', 'helm', 'kubectl-helm-aws']],
    environments: [devNamespace: 'dev'],
    preview: [namespacePrefix: 'pr', landscapeRelease: 'landscape', baselineRelease: 'baseline'],
    timeouts: [testMinutes: 20, qualityMinutes: 15, buildMinutes: 30, integrationMinutes: 30, securityMinutes: 30],
    sonarQube: [server: 'SonarQube'],
    commands: [mavenTest: 'mvn -B verify', qualityGate: 'mvn -B verify', sonarQubeScan: 'mvn -B sonar:sonar', integrationTest: 'mvn -B verify -Pintegration'],
    credentials: [:], notifications: [:]
  ]
}
