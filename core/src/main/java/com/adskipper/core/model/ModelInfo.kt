package com.adskipper.core.model

data class ModelFile(val name: String, val size: Long, val sha256: String) {
    val urls: List<String> get() = listOf(
        "https://modelscope.cn/models/ggml-org/InternVL3-2B-Instruct-GGUF/resolve/master/$name",
        "https://huggingface.co/ggml-org/InternVL3-2B-Instruct-GGUF/resolve/${ModelCatalog.REVISION}/$name",
        "https://hf-mirror.com/ggml-org/InternVL3-2B-Instruct-GGUF/resolve/${ModelCatalog.REVISION}/$name",
    )
}

object ModelCatalog {
    const val REVISION = "f763aa88f2ad2dbd5246350660fec7d50a20a7b0"
    val files = listOf(
        ModelFile("InternVL3-2B-Instruct-Q4_K_M.gguf", 1_116_758_816L,
            "dc36eddc05ff1db5e11e0aa38efe7a5063b045aa5350c3b1a2510f3ff9107179"),
        ModelFile("mmproj-InternVL3-2B-Instruct-Q8_0.gguf", 337_012_000L,
            "a91c525291f65b0469f82544e82f202c4df1c093268f0ce27deec10664078a0c"),
    )
}
