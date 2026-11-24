import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
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
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
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

tasks.test {
    // Каталог для PNG графа из FlowResultPanelTest: ./gradlew test -Pflowtrace.png.dir=/путь
    providers.gradleProperty("flowtrace.png.dir").orNull?.let { systemProperty("flowtrace.png.dir", it) }
}
