package deployers

class VmDeployer implements Serializable {
    def steps
    
    VmDeployer(steps) {
        this.steps = steps
    }
    
    def deploy(Map deployConfig) {
        steps.echo "Targeting KVM Virtual Machines via automated Ansible Playbook..."
        
        def nexusUrl = "http://enterprise.internal"
        def version = steps.env.BUILD_NUMBER
        def downloadUrl = "${nexusUrl}/org/enterprise/${deployConfig.artifact_name}/${version}/${deployConfig.artifact_name}-${version}.jar"
        
        // Bind the target team's specific SSH keys securely
        steps.sshagent([deployConfig.ssh_credential_id]) {
            // Reformat host array to standard Ansible comma-separated execution target syntax
            def targetHosts = deployConfig.hosts.join(',') + ','
            
            // Execute the systemwide standard playbook template located on the Jenkins orchestrator filesystem
            steps.sh """
                ansible-playbook -i "${targetHosts}" \
                /opt/jenkins/shared-playbooks/deploy-via-nexus.yml \
                --extra-vars "deploy_path=${deployConfig.deploy_path} download_url=${downloadUrl}"
            """
        }
    }
}
