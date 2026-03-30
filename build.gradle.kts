import java.util.Base64

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.2"
    id("maven-publish")
    id("jacoco")
}

fun properties(key: String) = project.findProperty(key).toString()
val isRelease = System.getProperty("release").toBoolean()
val oscToken: String = System.getProperty("gradle.wrapperOscToken") ?: ""
val mavenUser: String = System.getProperty("gradle.wrapperUser") ?: ""
val mavenPassword: String = System.getProperty("gradle.wrapperPassword") ?: ""
val hasCredentials = mavenUser.isNotEmpty() && mavenPassword.isNotEmpty()
val hasOscToken = oscToken.isNotEmpty()

group = properties("pluginGroup")
version = properties("pluginVersion")
version = if (isRelease) version else "$version-SNAPSHOT"

// Configure IntelliJ Platform Gradle Plugin
// Read more: [https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"), {
            useInstaller = false
        })
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        bundledPlugin("Git4Idea")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.assertj:assertj-core:3.22.0")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}

repositories {
    // /* Don't use in production: */ mavenLocal()

    if (hasOscToken) {
        maven {
            name = "sberosc-intellij-repository-releases"
            url = uri("https://sberosc.sigma.sbrf.ru/repo/maven/jetbrains_intellij_repository/releases")
            credentials {
                username = "token"
                password = oscToken
            }
        }
    }
    if (hasCredentials) {
        maven {
            name = "public"
            url = uri("https://nexus-ci.delta.sbrf.ru/repository/public/")
            credentials {
                username = mavenUser
                password = mavenPassword
            }
        }
        maven {
            name = "central"
            url = uri("https://nexus-ci.delta.sbrf.ru/repository/central/")
            credentials {
                username = mavenUser
                password = mavenPassword
            }
            content {
                includeGroup("com.google.code.gson")
            }
        }
        maven {
            name = "maven-lib-int"
            url = uri("https://nexus-ci.delta.sbrf.ru/repository/maven-lib-int")
            credentials {
                username = mavenUser
                password = mavenPassword
            }
            metadataSources {
                mavenPom()
                gradleMetadata()
            }
        }
        maven {
            name = "maven-sberosc-cache"
            url = uri("https://nexus-ci.delta.sbrf.ru/repository/maven-sberosc-cache")
            credentials {
                username = mavenUser
                password = mavenPassword
            }
            metadataSources {
                mavenPom()
                gradleMetadata()
            }
        }
        maven {
            name = "maven-sberosc-cache-intellij-dependencies"
            url = uri("https://nexus-ci.delta.sbrf.ru/repository/maven-sberosc-cache/intellij-dependencies")
            credentials {
                username = mavenUser
                password = mavenPassword
            }
            metadataSources {
                mavenPom()
                gradleMetadata()
            }
        }
    }
    if (hasOscToken) {
        maven {
            name = "sberosc-intellij-dependencies"
            url = uri("https://sberosc.sigma.sbrf.ru/repo/maven/jetbrains_redirect/intellij-dependencies/")
            credentials {
                username = "token"
                password = oscToken
            }
        }
        maven {
            name = "sberosc-central"
            url = uri("https://sberosc.sigma.sbrf.ru/repo/maven/central/")
            credentials {
                username = "token"
                password = oscToken
            }
        }
        maven {
            name = "sberosc-intellij-repository"
            url = uri("https://sberosc.sigma.sbrf.ru/repo/maven/jetbrains_intellij_repository/")
            credentials {
                username = "token"
                password = oscToken
            }
        }
    }

    mavenCentral()

    intellijPlatform {
        localPlatformArtifacts()
        defaultRepositories()
    }
}

intellijPlatform {
    pluginConfiguration {
        name = properties("pluginName")
        id = properties("pluginId")
        version = project.version as String
        ideaVersion {
            sinceBuild = properties("pluginSinceBuild")
            untilBuild = properties("pluginUntilBuild")
        }
    }

    pluginVerification {
        ides {
            create("IC", "2024.2.1", { useInstaller = false })
            create("IC", "2025.1.3", { useInstaller = false })
            repositories {
                intellijPlatform {
                    localPlatformArtifacts()
                }
            }
        }
    }

    // Если нужна сборка searchable options - закомментируйте строку "buildSearchableOptions = false"
    buildSearchableOptions = false
}

sourceSets {
    main {
        kotlin {
            srcDir("src/main/changelogai")
        }
    }
}

tasks {

    initializeIntellijPlatformPlugin {
        offline.set(true)
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
        finalizedBy(jacocoTestReport)
    }

    jacocoTestReport {
        dependsOn(test)
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
        // Исходники в нестандартном месте: src/main/changelogai
        sourceDirectories.setFrom(files("src/main/changelogai"))
        classDirectories.setFrom(files(
            fileTree("build/classes/kotlin/main") {
                exclude("**/*\$*.class")  // анонимные классы
            }
        ))
    }

    jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    withType<Jar> {
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    }

    patchPluginXml {
        val descriptionFile = project.file("DESCRIPTION.html")
        var descriptionHtml = descriptionFile.readText(Charsets.UTF_8)
        val gifFile = project.file("src/main/resources/META-INF/GigaGit_demo.gif")

        if (!gifFile.exists()) {
            throw GradleException("GIF file not found: ${gifFile.path}")
        }

        val gifBytes = gifFile.readBytes()
        val base64Gif = Base64.getEncoder().encodeToString(gifBytes)

        descriptionHtml = descriptionHtml.replace("%%DEMO_GIF_BASE64%%", "data:image/gif;base64,$base64Gif")

        pluginDescription.set(descriptionHtml)
        changeNotes.set(project.file("CHANGELOG.html").readText())
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = properties("pluginId")
            groupId = project.group as String
            version = project.version as String
            artifact("build/distributions/${project.name}-${project.version}.zip") {
                extension = "zip"
            }
        }
    }
    repositories {
        maven {
            val releaseUrl = "https://nexus-ci.delta.sbrf.ru/repository/maven-lib-release"
            val snapshotUrl = "https://nexus-ci.delta.sbrf.ru/repository/maven-lib-dev"
            url = uri(if (isRelease) releaseUrl else snapshotUrl)
            credentials {
                username = System.getProperty("gradle.wrapperUser")
                password = System.getProperty("gradle.wrapperPassword")
            }
        }
    }
}

tasks.named("publishAllPublicationsToMavenRepository") {
    dependsOn("buildPlugin")
}
tasks.named("publishMavenPublicationToMavenLocal") {
    dependsOn("buildPlugin")
}
tasks.named("publishMavenPublicationToMavenRepository") {
    dependsOn("buildPlugin")
}
tasks.named("publishToMavenLocal") {
    dependsOn("buildPlugin")
}