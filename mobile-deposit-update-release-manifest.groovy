// Application being triggered is supplied in the variable: app
// Revision is supplied in the variable: revision

def productName='mobile-deposit'
def downstreamJob="$productName-release-build"

if (revision=="") {
    error 'No revision specified'
}

// log "Received update", "$app:$revision"

node("linux") {
    stage('Reading Manifest') {
        try {
            step([$class: 'CopyArtifact', filter: 'manifest', projectName:env.JOB_NAME, selector: [$class: 'StatusBuildSelector', stable: false]])
            versions = readPropertiesFromFile ("manifest")
        } catch (Exception e) {
            echo e.toString()
            versions = new Properties()
        }
    }
    stage('Merging Manifest') {
        versions[app]=revision
    }

    stage('Writing Manifest') {
        writePropertiesFile(versions, "manifest")
        archive 'manifest'
    }
}

stage('Triggering Release Build') {
    log "Trigger build", "Triggering a new build"
    build job: downstreamJob, propagate: false, wait: false
}


def writePropertiesFile(props, file) {
    log "WriteProperties", "File = $file"
    writeFile file: file, text: writeProperties(props)
}

@NonCPS def writeProperties (props) {
    def sw = new StringWriter()
    props.store(sw, null)
    return sw.toString()
}

def readPropertiesFromFile (file) {
    log "ReadProperties", "File = $file"
    def str = readFile file: file, charset : 'utf-8'
    def sr = new StringReader(str)
    def props = new Properties()
    props.load(sr)
    return props
}



def log (step, msg) {

    echo """************************************************************
Step: $step
$msg
************************************************************"""
}
