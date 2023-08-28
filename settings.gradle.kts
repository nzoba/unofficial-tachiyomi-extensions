include(":core")

// all the directories under /lib instead of manually adding each to a list
File(rootDir, "lib").eachDir {
    val libName = it.name
    include(":lib-$libName")
    project(":lib-$libName").projectDir = File("lib/$libName")
}

if (System.getenv("CI") == null || System.getenv("CI_PUSH") == "true") {
    // Local development or full build for push

    // Loads all extensions
    File(rootDir, "src").eachDir { dir ->
        dir.eachDir { subdir ->
            val name = ":extensions:individual:${dir.name}:${subdir.name}"
            include(name)
            project(name).projectDir = File("src/${dir.name}/${subdir.name}")
        }
    }

    /**
     * If you're developing locally and only want to work with a single module,
     * comment out the parts above and uncomment below.
     */
    // val lang = "all"
    // val name = "mmrcms"
    // include(":${lang}-${name}")
    // project(":${lang}-${name}").projectDir = File("src/${lang}/${name}")
} else {
    // Running in CI (GitHub Actions)

    val lang = System.getenv("CI_MATRIX_LANG")

    // Loads all extensions
    File(rootDir, "src").eachDir { dir ->
        if (dir.name == lang) {
            dir.eachDir { subdir ->
                val name = ":extensions:individual:${dir.name}:${subdir.name}"
                include(name)
                project(name).projectDir = File("src/${dir.name}/${subdir.name}")
            }
        }
    }
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
