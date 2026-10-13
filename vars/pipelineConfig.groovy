#!/usr/bin/groovy

def call(body) {
    // ==========================================
    // 1. ADMIN CENTRAL CONFIGURATIONS & GUARDRAILS
    // ==========================================
    final String ARTIFACTORY_HOST = "192.168.122.154:5000" 
    final String SECURE_SCAN_IMAGE = "aquasec/trivy:latest" 
    final double MAX_CPU = 16.0
    final double MAX_MEMORY_GB = 32.0

    // Central local cache repository sitting directly on your massive agent
    final String HOST_CACHE_BASE = "/home/jenkins-agent/caches"

    // Target the single massive administrator agent
    node('remote-node-4-ci') {
        currentBuild.result = "SUCCESS"

        stage('Checkout & Validation') {
            checkout scm 
            
            if (!fileExists('pipeline-config.yaml')) {
                error "CRITICAL ERROR: 'pipeline-config.yaml' is missing from the repository root."
            }
            
            def config = readYaml file: 'pipeline-config.yaml'
            
            // Extract resource boundaries
            def requestedCpu = (config.resources?.cpu ?: "2").toDouble()
            def requestedMemStr = config.resources?.memory ?: "4Gi"

            double requestedMemGB = 0.0
            String cleanMemStr = requestedMemStr.toUpperCase()
            double numericalValue = cleanMemStr.replaceAll(/[^\d\.]/, '').toDouble()

            if (cleanMemStr.contains("M")) { requestedMemGB = numericalValue / 1024.0 }
            else if (cleanMemStr.contains("G")) { requestedMemGB = numericalValue }
            else { requestedMemGB = numericalValue / (1024.0 * 1024.0 * 1024.0) }

            if (requestedCpu > MAX_CPU || requestedMemGB > MAX_MEMORY_GB) {
                error "SECURITY VIOLATION: Requested resources exceed limits."
            }

            String dockerMemoryFlag = requestedMemStr.toLowerCase().replaceAll(/i/, '')

            // Map stack strings to baseline official Docker images
            def imageMap = ['java': 'maven', 'go': 'golang', 'python': 'python', 'rust': 'rust']
            String stackKey = config.stack?.toLowerCase()
            String baseImageName = imageMap[stackKey]
            
            if (!baseImageName) { error "UNSUPPORTED STACK: '${config.stack}'" }

            // ==========================================
            // LOCAL HOST PERSISTENT VOLUME EXTRACTION
            // ==========================================
            String cacheVolumeFlag = ""
            switch(stackKey) {
                case 'java':   cacheVolumeFlag = "-v ${HOST_CACHE_BASE}/maven:/root/.m2"; break
                case 'go':     cacheVolumeFlag = "-v ${HOST_CACHE_BASE}/go:/go/pkg/mod"; break
                case 'python': cacheVolumeFlag = "-v ${HOST_CACHE_BASE}/pip:/root/.cache/pip"; break
                case 'rust':   cacheVolumeFlag = "-v ${HOST_CACHE_BASE}/rust:/usr/local/cargo/registry"; break
            }

            // Map developer strings to real upstream docker tags
            def upstreamImageMap = [
                'java-1.17': 'maven:3.9.6-eclipse-temurin-17',
                'java-1.21': 'maven:3.9.6-eclipse-temurin-21',
                'go-1.21':   'golang:1.21-bookworm',
                'python-3':  'python:3.11-slim',
                'rust-1':    'rust:1.75-slim'
            ]
            
            String versionKey = config.stackVersion?.toLowerCase()
            String lookupString = "${stackKey}-${versionKey}"
            String upstreamTarget = upstreamImageMap[lookupString] ?: upstreamImageMap["${stackKey}-1.21"]

            String buildRuntimeImage = "${ARTIFACTORY_HOST}/${baseImageName}:${config.stackVersion}"

            // Pull public upstream image name first, then tag it locally
            sh """
                docker image inspect ${buildRuntimeImage} >/dev/null 2>&1 || {
                    docker pull ${upstreamTarget}
                    docker tag ${upstreamTarget} ${buildRuntimeImage}
                }
            """

            // Dynamic reflection resolution
            def handler = null
            String className = "handlers.${stackKey.capitalize()}Handler"
            try {
                Class handlerClass = this.class.classLoader.loadClass(className)
                handler = handlerClass.newInstance(this)
            } catch (ClassNotFoundException e) {
                error "CRITICAL CONFIGURATION ERROR: Helper class ${className} could not be resolved."
            }

            String containerName = "jenkins-build-${env.JOB_NAME.replaceAll(/[^a-zA-Z0-9]/, '-')}-${env.BUILD_NUMBER}"
            
            // ==========================================
            // 2. CONTAINERIZED STACK EXECUTION (HOST CACHED)
            // ==========================================
            try {
                // Ensure the localized cache folder directory maps out safely on the host agent file path
                sh "mkdir -p ${HOST_CACHE_BASE}/${stackKey == 'java' ? 'maven' : stackKey}"
                
                // Mount the persistent host cache location directly into the runtime context using --net=host
                sh "docker run -d --name ${containerName} --net=host --cpus=${requestedCpu} --memory=${dockerMemoryFlag} ${cacheVolumeFlag} -v ${env.WORKSPACE}:${env.WORKSPACE} -w ${env.WORKSPACE} ${buildRuntimeImage} tail -f /dev/null"
                
                stage('Pre-Build Steps') { handler.preBuild(containerName, config) }
                stage('Build') { handler.build(containerName, config) }
                stage('Test') { handler.test(containerName, config) }
            } finally {
                sh "docker rm -f ${containerName} || true"
            }

            // ==========================================
            // 3. MANDATORY ADMIN SECURITY COMPLIANCE
            // ==========================================
            stage('Mandatory Security Scan') {
                String scanContainerName = "jenkins-scan-${env.BUILD_NUMBER}"
                try {
                    sh "docker run --rm --name ${scanContainerName} -v ${env.WORKSPACE}:/apps ${SECURE_SCAN_IMAGE} fs --exit-code 1 --severity HIGH,CRITICAL /apps"
                } catch (Exception e) {
                    error "SECURITY COMPLIANCE FAILURE: Vulnerabilities discovered by admin tool."
                }
            }

            // ==========================================
            // 4. IMAGE DISTRIBUTION & PUBLISHING
            // ==========================================
            stage('Publish to Artifactory') {
                String targetImage = "${ARTIFACTORY_HOST}/${config.imageName}:${env.BRANCH_NAME}-${env.BUILD_NUMBER}"
                handler.packageAndPublish(targetImage, ARTIFACTORY_HOST)
            }
        }
    }
}

