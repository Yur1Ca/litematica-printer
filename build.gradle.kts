plugins {
    id("maven-publish")
    id("net.fabricmc.fabric-loom") version "1.17.20" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.17.20" apply false

    // https://github.com/ReplayMod/preprocessor
    // https://github.com/Fallen-Breath/preprocessor
    // https://jitpack.io/#Fallen-Breath/preprocessor
    id("com.replaymod.preprocess") version "c5abb4fb12"
}

preprocess {
    strictExtraMappings.set(false)

    val mc12111 = createNode("1.21.11", 12111, "mojang")
    val mc260100 = createNode("26.1", 260100, "mojang")
    val mc260200 = createNode("26.2", 260200, "mojang")
    val mc260300 = createNode("26.3", 260300, "mojang")

    mc12111.link(mc260100, file("versions/mapping-1.21.11-26.1.txt"))
    mc260200.link(mc260100, null)
    mc260300.link(mc260200, null)

    // See https://github.com/Fallen-Breath/fabric-mod-template/blob/1d72d77a1c5ce0bf060c2501270298a12adab679/build.gradle#L55-L63
    for (node in getNodes()) {
        findProject(node.project)
            ?.ext
            ?.set("mcVersion", node.mcVersion)
    }
}
