// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

// Удалён неверный блок java { } — для Android проекта на корневом уровне без 'java' plugin это недопустимо.
// Используем org.gradle.java.home в gradle.properties + module compileOptions (Java 17).