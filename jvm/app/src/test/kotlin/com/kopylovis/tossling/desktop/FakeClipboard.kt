package com.kopylovis.tossling.desktop

import java.util.concurrent.CopyOnWriteArrayList

class FakeClipboard : SystemClipboard {

    @Volatile var current: Clip? = null
    @Volatile var private = false
    val written = CopyOnWriteArrayList<Clip>()
    private var onChange: () -> Unit = {}

    override fun watch(onChange: () -> Unit) {
        this.onChange = onChange
    }

    override fun read(): Clip? = current

    override fun isPrivate(): Boolean = private

    override fun write(clip: Clip) {
        current = clip
        written.add(clip)
        onChange()
    }

    fun copy(clip: Clip, isPrivate: Boolean = false) {
        private = isPrivate
        current = clip
        onChange()
    }
}
