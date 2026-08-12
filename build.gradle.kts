import java.util.Properties

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")

    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.file("detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        reports {
            html.required.set(true)
            xml.required.set(true)
            txt.required.set(false)
            sarif.required.set(false)
            md.required.set(false)
        }
    }
}

// Single source of truth for versionName/versionCode, shared by :phone and :watch so the two
// APKs can never drift apart. See version.properties and CLAUDE.md ("Схема версионирования").
val wmwVersionProps = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
extra["wmwVersionName"] = wmwVersionProps.getProperty("versionName").trim()
extra["wmwVersionCode"] = wmwVersionProps.getProperty("versionCode").trim().toInt()

// Release signing config — kept outside git entirely (keystore.properties is gitignored, the
// .jks file itself lives outside the repo). Debug builds don't need this file; only
// :phone:assembleRelease / :watch:assembleRelease do, and fail with a clear error if it's missing.
val wmwKeystorePropsFile = rootProject.file("keystore.properties")
val wmwKeystorePropsExist = wmwKeystorePropsFile.exists()
val wmwKeystoreProps = Properties().apply {
    if (wmwKeystorePropsExist) wmwKeystorePropsFile.inputStream().use { load(it) }
}
extra["wmwKeystorePropsExist"] = wmwKeystorePropsExist
extra["wmwKeystoreStoreFile"] = wmwKeystoreProps.getProperty("storeFile")
extra["wmwKeystoreStorePassword"] = wmwKeystoreProps.getProperty("storePassword")
extra["wmwKeystoreKeyAlias"] = wmwKeystoreProps.getProperty("keyAlias")
extra["wmwKeystoreKeyPassword"] = wmwKeystoreProps.getProperty("keyPassword")

gradle.taskGraph.whenReady {
    val needsRelease = allTasks.any { it.name.contains("Release", ignoreCase = true) }
    if (needsRelease && !wmwKeystorePropsExist) {
        throw GradleException(
            "keystore.properties not found at ${wmwKeystorePropsFile.absolutePath}. " +
                "Release builds need storeFile/storePassword/keyAlias/keyPassword in that file " +
                "(gitignored, outside git). Debug builds don't need it.",
        )
    }
}
