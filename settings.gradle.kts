rootProject.name = "AIOAssist"

pluginManagement {
    repositories {
        val oscToken: String = System.getProperty("gradle.wrapperOscToken") ?: ""
        val mavenUser: String = System.getProperty("gradle.wrapperUser") ?: ""
        val mavenPassword: String = System.getProperty("gradle.wrapperPassword") ?: ""
        val hasCredentials = mavenUser.isNotEmpty() && mavenPassword.isNotEmpty()
        val hasOscToken = oscToken.isNotEmpty()

        if (hasCredentials) {
            maven {
                name = "public"
                url = uri("https://nexus-ci.delta.sbrf.ru/repository/public")
                credentials {
                    username = mavenUser
                    password = mavenPassword
                }
                content {
                    includeGroup("com.google.code.gson")
                    // TODO убрать после разблокировки undertow-core в sberosc
                    includeGroup("io.undertow")
                }
            }
        }
        if (hasOscToken) {
            maven {
                name = "gradle-plugins"
                url = uri("https://sberosc.sigma.sbrf.ru/repo/maven/gradle_plugins")
                credentials {
                    username = "token"
                    password = oscToken
                }
            }
        }
        if (hasCredentials) {
            maven {
                name = "epoch"
                url = uri("https://nexus-ci.delta.sbrf.ru/repository/maven-lib-int/ru/sbrf/epoch")
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

        gradlePluginPortal()
        mavenCentral()
    }
}