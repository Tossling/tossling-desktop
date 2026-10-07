package com.kopylovis.tossling.desktop

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object Images {

    private const val MAX_IMAGE = 15_000_000
    private const val QUALITY = 0.85f
    private val SIDES = listOf(4096, 2048)

    fun payload(image: Clip.Image): Clip.Image? {
        if (image.bytes.size <= MAX_IMAGE / 2) return image
        val source = ImageIO.read(ByteArrayInputStream(image.bytes)) ?: return null
        jpeg(source)?.takeIf { it.size <= MAX_IMAGE }?.let { return Clip.Image(bytes = it, mime = "image/jpeg") }
        val side = maxOf(source.width, source.height)
        for (limit in SIDES.filter { side > it }) {
            val scale = limit.toDouble() / side
            val scaled = BufferedImage((source.width * scale).toInt(), (source.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
            scaled.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                drawImage(source, 0, 0, scaled.width, scaled.height, null)
            }.dispose()
            jpeg(scaled)?.takeIf { it.size <= MAX_IMAGE }?.let {
                Log.write("the image is large: scaled down to ${scaled.width}×${scaled.height}")
                return Clip.Image(bytes = it, mime = "image/jpeg")
            }
        }
        Log.write("skipped: the image is too large")
        return null
    }

    private fun jpeg(image: BufferedImage): ByteArray? {
        val opaque = if (image.type == BufferedImage.TYPE_INT_RGB) image else BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also {
            it.createGraphics().apply { drawImage(image, 0, 0, java.awt.Color.WHITE, null) }.dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").asSequence().firstOrNull() ?: return null
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = QUALITY
            }
            writer.write(null, IIOImage(opaque, null, null), param)
        }
        writer.dispose()
        return out.toByteArray()
    }
}
