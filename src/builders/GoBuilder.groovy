package builders

class GoBuilder implements Serializable {
    def steps
    
    GoBuilder(steps) {
        this.steps = steps
    }
    
    def build(Map yamlConfig) {
        steps.kubernetes {
            yaml """
            apiVersion: v1
            kind: Pod
            spec:
              containers:
              - name: go-builder
                image: golang:${yamlConfig.version}-alpine
                command: ['cat']
                tty: true
            """
        }
        steps.container('go-builder') {
            steps.sh "go build -o target/app main.go"
            steps.sh "go test ./..."
            // Nexus upload logic for binaries goes here
        }
    }
}
