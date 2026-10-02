// 国内镜像仅加速其真正托管的坐标：用 content 过滤限定 group，
// 避免 Gradle 对镜像不存在的路径发请求（阿里云 google 仓不托管
// jakarta.*/org.apache.httpcomponents.* 等，曾返回 502 导致仓库被
// 整体禁用，进而 :app:lintVitalAnalyzeRelease 解析失败）。
// google()/mavenCentral() 保留为权威回落。
pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("org\\.jetbrains.*")
                includeGroupByRegex("org\\.gradle.*")
            }
        }
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.firebase.*")
            }
        }
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google\\.android.*")
                includeGroupByRegex("com\\.google\\.firebase.*")
            }
        }
        maven("https://maven.aliyun.com/repository/public")
        google()
        mavenCentral()
    }
}

rootProject.name = "AppListUploadBlocker"
include(":app")
