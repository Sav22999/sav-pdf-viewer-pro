// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        jcenter()
        mavenCentral()
    }
    dependencies {
        classpath ("com.android.tools.build:gradle:7.4.1")
        classpath ("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.21")

        // NOTE: Do not place your application dependencies here; they belong
        // in the individual module build.gradle files
    }
}

allprojects {
    repositories {
        google()
        jcenter()
        mavenCentral()
    }
}

tasks.register("clean",Delete::class.java) {
    delete(rootProject.buildDir)
}
