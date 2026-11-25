import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
}

/** Путь к установленной GoLand из ~/.gradle/gradle.properties или -PgoLandPath=…; без него GoLand скачивается. */
val goLandPath = providers.gradleProperty("goLandPath")

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Не генерировать мосты к default-методам интерфейсов платформы (ToolWindowFactory и др.).
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

    intellijPlatform {
        if (goLandPath.isPresent) {
            local(goLandPath)
        } else {
            goland(providers.gradleProperty("platformVersion"))
        }
        bundledPlugin("org.jetbrains.plugins.go")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Описание для Marketplace — раздел README.md между маркерами Plugin description.
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"
            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("README.md: нет раздела $start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        // Change notes — раздел текущей версии из CHANGELOG.md (или Unreleased, если его ещё нет).
        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    // Секреты подписи и публикации — только из переменных окружения, в репозитории их нет.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // 0.4.0-beta.1 уходит в канал beta, версия без суффикса — в основной.
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

    pluginVerification {
        ides {
            if (goLandPath.isPresent) {
                local(file(goLandPath.get()))
            } else {
                recommended()
            }
        }
    }
}

changelog {
    groups.empty()
    repositoryUrl = "https://github.com/lookandhate/flowtrace"
}

tasks.publishPlugin {
    dependsOn(tasks.patchChangelog)
}

// Проверяет только что подписанный архив: без явной зависимости Gradle считает связь неявной и падает.
tasks.verifyPluginSignature {
    dependsOn(tasks.signPlugin)
}

tasks.test {
    // Каталог для PNG графа из FlowResultPanelTest: ./gradlew test -Pflowtrace.png.dir=/путь
    providers.gradleProperty("flowtrace.png.dir").orNull?.let { systemProperty("flowtrace.png.dir", it) }
    // Скриншоты для Marketplace: ./gradlew test --tests '*MarketplaceScreenshots*' -Pflowtrace.screenshots.dir=/путь
    providers.gradleProperty("flowtrace.screenshots.dir").orNull?.let { systemProperty("flowtrace.screenshots.dir", it) }
}
