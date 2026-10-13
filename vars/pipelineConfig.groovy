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
            
            // Extract and validate resource configurations
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
            // MITIGATION 1: JOB-ISOLATED POISON-PROOF HOST CACHING
            // ==========================================
            // Safe filesystem path character handling for branch jobs
            String safeJobPath = env.JOB_NAME.replaceAll(/[^a-zA-Z0-9]/, '-')
            String hostJobCacheDir = "${HOST_CACHE_BASE}/${safeJobPath}"
            
            String cacheVolumeFlag = ""
            switch(stackKey) {
                case 'java':   cacheVolumeFlag = "-v ${hostJobCacheDir}/maven:/root/.m2"; break
                case 'go':     cacheVolumeFlag = "-v ${hostJobCacheDir}/go:/go/pkg/mod"; break
                case 'python': cacheVolumeFlag = "-v ${hostJobCacheDir}/pip:/root/.cache/pip"; break
                case 'rust':   cacheVolumeFlag = "-v ${hostJobCacheDir}/rust:/usr/local/cargo/registry"; break
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

            // Dynamic reflection resolution for language handlers
            def handler = null
            String className = "handlers.${stackKey.capitalize()}Handler"
            try {
                Class handlerClass = this.class.classLoader.loadClass(className)
                handler = handlerClass.newInstance(this)
            } catch (ClassNotFoundException e) {
                error "CRITICAL CONFIGURATION ERROR: Helper class ${className} could not be resolved."
            }

            String containerName = "jenkins-build-${safeJobPath}-${env.BUILD_NUMBER}"
            
            // ==========================================
            // 2. CONTAINERIZED STACK EXECUTION
            // ==========================================
            try {
                // Pre-create the directory mapping layout on the local host agent
                sh "mkdir -p ${hostJobCacheDir}/maven ${hostJobCacheDir}/go ${hostJobCacheDir}/pip ${hostJobCacheDir}/rust"
                
                // Mount the job-isolated cache volume directly into the runtime context
                sh "docker run -d --name ${containerName} --net=host --cpus=${requestedCpu} --memory=${dockerMemoryFlag} ${cacheVolumeFlag} -v ${env.WORKSPACE}:${env.WORKSPACE} -w ${env.WORKSPACE} ${buildRuntimeImage} tail -f /dev/null"
                
                stage('Pre-Build Steps') { handler.preBuild(containerName, config) }
                stage('Build') { handler.build(containerName, config) }
                stage('Test') { handler.test(containerName, config) }
            } finally {
                sh "docker rm -f ${containerName} || true"
            }

            // ==========================================
            // 3. MANDATORY SECURITY SCAN (FIXED FOR 429 BY CACHE INJECTION)
            // ==========================================
            stage('Mandatory Security Scan') {
                echo "Running secure filesystem auditing via central tooling..."
                String scanContainerName = "jenkins-scan-${env.BUILD_NUMBER}"
                
                // Construct secondary map matching Trivy's expected internal lookups
                String trivyCacheFlag = ""
                if (stackKey == 'java') {
                    trivyCacheFlag = "-v ${hostJobCacheDir}/maven:/root/.m2"
                }
                
                try {
                    // Injecting the local host cache directory directly into Trivy container
                    sh "docker run --rm --name ${scanContainerName} ${trivyCacheFlag} -v ${env.WORKSPACE}:/apps ${SECURE_SCAN_IMAGE} fs --exit-code 1 --severity HIGH,CRITICAL /apps"
                } catch (Exception e) {
                    error "SECURITY COMPLIANCE FAILURE: Vulnerabilities discovered by admin tool. Blocking pipeline progression."
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

