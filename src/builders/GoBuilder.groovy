package builders

class GoBuilder implements Serializable {
    def steps
    
    GoBuilder(steps) {
        this.steps = steps
    }
    
    def build(Map yamlConfig) {
        def podXml = """
            apiVersion: v1
            kind: Pod
            spec:
              containers:
              - name: go-builder
                image: golang:${yamlConfig.version}-alpine
                command: ['cat']
                tty: true
        """
        
        steps.podTemplate(yaml: podXml) {
            steps.node(steps.POD_LABEL) {
                steps.container('go-builder') {
                    steps.checkout steps.scm
                    steps.sh "go build -o target/app main.go"
                    steps.sh "go test ./..."
                }
            }
        }
    }
}
