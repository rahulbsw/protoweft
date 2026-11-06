// Used by CI to build the templates against artifacts from `publishToMavenLocal`, before a release
// exists on Maven Central:
//   ./gradlew publishToMavenLocal -Pversion=0.0.0-templates
//   ./gradlew -p templates/<name> build --init-script templates/verify-with-maven-local.init.gradle.kts \
//       -PprotoweftVersion=0.0.0-templates
allprojects {
    repositories {
        mavenLocal {
            content { includeGroup("io.github.rahulbsw") }
        }
    }
}
