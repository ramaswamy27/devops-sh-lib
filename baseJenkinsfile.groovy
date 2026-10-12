// 1. Dynamic import of your Global Shared Library
// Replace 'global-shared-library' with the name you defined in Jenkins Global Configuration
// 'main' targets the stable production branch of your shared library repo
@Library('devops-sh-lib@main') _

// 2. Call the global variable step from the shared library
// This step will automatically look for the developer's local 'pipeline.yaml' marker file
runEnterprisePipeline()
