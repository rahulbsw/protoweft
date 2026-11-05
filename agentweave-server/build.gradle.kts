// The servlet hosting contract shared by embedded Jetty and the Spring Boot starter.
dependencies {
    api(project(":agentweave-core"))
    compileOnly("jakarta.servlet:jakarta.servlet-api:${project.property("servletApiVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testImplementation("jakarta.servlet:jakarta.servlet-api:${project.property("servletApiVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
