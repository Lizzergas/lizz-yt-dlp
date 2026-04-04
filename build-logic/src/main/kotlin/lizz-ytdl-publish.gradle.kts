import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension
import java.util.Base64

plugins {
    `maven-publish`
    signing
    id("org.jetbrains.dokka")
}

val javadocJar = tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
}

extensions.configure<PublishingExtension>("publishing") {
    repositories {
        maven(rootProject.layout.buildDirectory.dir("repo")) {
            name = "buildRepo"
        }
    }

    publications.withType(MavenPublication::class.java).configureEach {
        artifact(javadocJar)

        pom {
            name.set(project.name)
            description.set(project.description ?: project.requiredProperty("POM_DESCRIPTION"))
            url.set(project.requiredProperty("POM_URL"))

            licenses {
                license {
                    name.set(project.requiredProperty("POM_LICENSE_NAME"))
                    url.set(project.requiredProperty("POM_LICENSE_URL"))
                }
            }

            developers {
                developer {
                    id.set(project.requiredProperty("POM_DEVELOPER_ID"))
                    name.set(project.requiredProperty("POM_DEVELOPER_NAME"))
                }
            }

            scm {
                url.set(project.requiredProperty("POM_SCM_URL"))
                connection.set(project.requiredProperty("POM_SCM_CONNECTION"))
                developerConnection.set(project.requiredProperty("POM_SCM_DEV_CONNECTION"))
            }
        }
    }
}

val signingKeyId = propertyOrEnv("SIGNING_KEY_ID")
val signingKey = propertyOrEnv("SIGNING_KEY")?.normalizeSigningKey()
val signingPassword = propertyOrEnv("SIGNING_PASSWORD")

if (!signingKey.isNullOrBlank()) {
    extensions.configure<SigningExtension>("signing") {
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        sign(extensions.getByType(PublishingExtension::class.java).publications)
    }
}

fun Project.requiredProperty(name: String): String {
    return findProperty(name)?.toString()
        ?: throw GradleException("Missing required Gradle property: $name")
}

fun Project.propertyOrEnv(name: String): String? {
    return findProperty(name)?.toString() ?: System.getenv(name)
}

fun String.normalizeSigningKey(): String {
    val normalized = trim()
        .removeSurrounding("\"")
        .replace("\r\n", "\n")
        .replace("\r", "\n")

    if ("BEGIN PGP PRIVATE KEY BLOCK" in normalized) {
        return normalized.replace("\\n", "\n")
    }

    val decoded = runCatching {
        String(Base64.getDecoder().decode(normalized))
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .trim()
    }.getOrNull()

    return when {
        decoded != null && "BEGIN PGP PRIVATE KEY BLOCK" in decoded -> decoded
        else -> normalized
    }
}
