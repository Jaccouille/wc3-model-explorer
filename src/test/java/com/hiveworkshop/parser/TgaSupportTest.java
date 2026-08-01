package com.hiveworkshop.parser;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a TGA ImageIO reader is registered on the classpath and that
 * texture loading (which funnels through {@link ImageIO#read}) can decode TGA
 * bytes. Guards against the TGA plugin silently dropping out of the build.
 */
class TgaSupportTest {

    @Test
    void tgaReaderIsRegistered() {
        Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReadersBySuffix("tga");
        assertTrue(readers.hasNext(), "No ImageIO reader registered for the 'tga' suffix");
    }

    @Test
    void decodesTrueColorTga() throws Exception {
        // Plain v1 uncompressed 24-bit TGA (no footer), as custom WC3 textures ship.
        // Must be larger than ~44 bytes: the reader flushes past the 18-byte header
        // then seeks to end-26 to probe for a footer.
        byte[] tga = makeTgaV1(16, 16);

        BufferedImage img;
        try (javax.imageio.stream.ImageInputStream iis =
                     new javax.imageio.stream.MemoryCacheImageInputStream(
                             new ByteArrayInputStream(tga))) {
            Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(iis);
            assertTrue(readers.hasNext(), "No reader for TGA stream");
            javax.imageio.ImageReader reader = readers.next();
            // seekForwardOnly=false is required: the TGA reader seeks backward.
            reader.setInput(iis, false, false);
            img = reader.read(0);
            reader.dispose();
        }
        assertNotNull(img, "Decoding returned null for a valid TGA");
        assertEquals(16, img.getWidth());
        assertEquals(16, img.getHeight());
        assertEquals(0xFF0000, img.getRGB(0, 0) & 0xFFFFFF, "Decoded pixel colour mismatch");
    }

    /** Builds a minimal v1 uncompressed 24-bit (BGR) TGA, all pixels red, no footer. */
    private static byte[] makeTgaV1(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);            // id length
        out.write(0);            // color map type: none
        out.write(2);            // image type: uncompressed true-color
        out.write(new byte[5], 0, 5); // color map spec (unused)
        writeLE16(out, 0);       // x origin
        writeLE16(out, 0);       // y origin
        writeLE16(out, width);
        writeLE16(out, height);
        out.write(24);           // pixel depth (bits)
        out.write(0x20);         // image descriptor: origin top-left
        for (int i = 0; i < width * height; i++) {
            out.write(0);        // blue
            out.write(0);        // green
            out.write(255);      // red
        }
        return out.toByteArray();
    }

    private static void writeLE16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }
}
