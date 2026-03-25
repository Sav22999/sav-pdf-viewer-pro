import org.gradle.api.tasks.Delete

// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral() // Replaced jcenter()
    }
    dependencies {
        classpath ("com.android.tools.build:gradle:8.8.2")
        classpath ("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.24")

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

allprojects {
    repositories {
        google()
        mavenCentral() // Replaced jcenter()
    }
}

tasks.register<Exec>("fixNativeAlignment") {
    group = "alignment"
    description = "Extracts and aligns native libraries to 16KB page boundaries."
    commandLine("python3", "fix_elf_alignment.py")
}

tasks.register("clean",Delete::class.java) {
    delete(rootProject.layout.buildDirectory)
}
