plugins {
    application
}

dependencies {
    implementation(project(":agentweave-langchain4j"))
    implementation(project(":agentweave-a2a"))
    implementation(project(":agentweave-server-jetty"))
    // Any LangChain4j chat model works; OpenAI's also talks to OpenAI-compatible servers (e.g. Ollama).
    implementation("dev.langchain4j:langchain4j-open-ai:${project.property("langchain4jVersion")}")
    runtimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}

application {
    mainClass.set("io.agentweave.examples.langchain4j.LangChain4jExample")
}

tasks.withType<PublishToMavenRepository>().configureEach { enabled = false }
