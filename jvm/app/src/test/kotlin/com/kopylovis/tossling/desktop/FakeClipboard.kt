package com.kopylovis.tossling.desktop

import java.util.concurrent.CopyOnWriteArrayList

class FakeClipboard : SystemClipboard {

    @Volatile var current: Clip? = null
    @Volatile var private = false
    val written = CopyOnWriteArrayList<Clip>()
    val secrets = CopyOnWriteArrayList<Clip>()
    private var onChange: () -> Unit = {}

    override fun watch(onChange: () -> Unit) {
        this.onChange = onChange
    }

    override fun read(): Clip? = current

    override fun isPrivate(): Boolean = private

    override fun write(clip: Clip, secret: Boolean) {
        current = clip
        written.add(clip)
        if (secret) secrets.add(clip)
        onChange()
    }

    fun copy(clip: Clip, isPrivate: Boolean = false) {
        private = isPrivate
        current = clip
        onChange()
    }
}
