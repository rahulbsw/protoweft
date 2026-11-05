// Embedded Jetty 12 (Jakarta EE 11, Servlet 6.1) host for plain-Java applications.
dependencies {
    api(project(":agentweave-server"))
    api("jakarta.servlet:jakarta.servlet-api:${project.property("servletApiVersion")}")
    implementation("org.eclipse.jetty.ee11:jetty-ee11-servlet:${project.property("jettyVersion")}")

    testImplementation(platform("org.junit:junit-bom:${project.property("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:${project.property("assertjVersion")}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${project.property("slf4jVersion")}")
}
