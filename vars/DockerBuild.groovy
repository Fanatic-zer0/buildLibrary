/**
 * Global variable backing DockerBuild.<method>() calls in Jenkinsfiles.
 *
 * Builds container images with rootless BuildKit (no Docker daemon / docker.sock),
 * scans the unpushed OCI archive with Trivy, and only pushes to the registry with
 * skopeo once the scan gate passes. Requires a Kubernetes agent pod exposing
 * `builder`, `trivy`, and `skopeo` containers (see resources/jenkins/agents/buildagent-jdk21.yaml).
 */

def call(Map args) {
  build(args)
  if (args.scan != false) { scan(args) }
  if (args.push != false) { push(args) }
  cleanup(args)
}

def build(Map args) {
  requireArgs(args, ['image'])
  List<String> tags = tagsFor(args)
  String archive = archiveFor(args)
  String dockerfile = args.dockerfile ?: 'Dockerfile'
  String dockerfileDir = dockerfile.contains('/') ? dockerfile.substring(0, dockerfile.lastIndexOf('/')) : '.'
  String dockerfileName = dockerfile.contains('/') ? dockerfile.substring(dockerfile.lastIndexOf('/') + 1) : dockerfile
  String context = args.context ?: '.'
  String platform = args.platform ?: 'linux/amd64'
  String buildArgOpts = (args.buildArgs ?: [:]).collect { k, v -> "--opt build-arg:${k}='${v}'" }.join(' ')

  inContainer(args.builderContainer ?: 'builder') {
    timeout(time: (args.buildMinutes ?: 30) as int, unit: 'MINUTES') {
      sh(label: "Build ${args.image}", script: """#!/usr/bin/env bash
        set -euo pipefail
        buildctl-daemonless.sh build \\
          --frontend dockerfile.v0 \\
          --local context='${context}' \\
          --local dockerfile='${dockerfileDir}' \\
          --opt filename='${dockerfileName}' \\
          --opt platform='${platform}' \\
          ${buildArgOpts} \\
          --output type=oci,dest='${archive}'
      """)
    }
  }
  echo "Built ${args.image} -> ${tags.join(', ')} (${archive})"
}

def scan(Map args) {
  String archive = archiveFor(args)
  String severity = args.severity ?: 'CRITICAL,HIGH'
  boolean failOnFindings = args.failOnFindings != false
  inContainer(args.scanContainer ?: 'trivy') {
    timeout(time: (args.scanMinutes ?: 15) as int, unit: 'MINUTES') {
      sh(label: "Scan ${archive}", script: """#!/usr/bin/env bash
        set -euo pipefail
        trivy image --input '${archive}' --severity '${severity}' --ignore-unfixed --no-progress --exit-code ${failOnFindings ? 1 : 0}
      """)
    }
  }
}

def push(Map args) {
  requireArgs(args, ['image', 'registryCredential'])
  List<String> tags = tagsFor(args)
  String archive = archiveFor(args)
  inContainer(args.pushContainer ?: 'skopeo') {
    withCredentials([usernamePassword(credentialsId: args.registryCredential, usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_PASSWORD')]) {
      tags.each { tag ->
        sh(label: "Push ${args.image}:${tag}", script: """#!/usr/bin/env bash
          set -euo pipefail
          skopeo copy --dest-creds "\$REGISTRY_USER:\$REGISTRY_PASSWORD" \\
            oci-archive:'${archive}' \\
            docker://${args.image}:${tag}
        """)
      }
    }
  }
}

def cleanup(Map args) {
  String archive = archiveFor(args)
  sh(label: 'Remove local image archive', script: "rm -f '${archive}' || true")
}

private List<String> tagsFor(Map args) {
  List<String> tags = args.tags ? new ArrayList<>(args.tags) : (args.tag ? [args.tag] : [])
  if (!tags) { error 'DockerBuild requires either "tag" or a non-empty "tags" list.' }
  return tags
}

private String archiveFor(Map args) {
  return args.archive ?: "${(args.image ?: 'image').replaceAll('[^A-Za-z0-9._-]', '_')}.tar"
}

private void inContainer(String name, Closure body) {
  container(name) { body() }
}

private void requireArgs(Map args, List keys) {
  keys.each { key -> if (!args[key]) { error "DockerBuild requires '${key}' argument." } }
}
