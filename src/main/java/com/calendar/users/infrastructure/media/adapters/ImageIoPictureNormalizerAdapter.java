package com.calendar.users.infrastructure.media.adapters;

import com.calendar.users.domain.ports.PictureNormalizer;
import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

/**
 * Normalizes pictures with the JDK's ImageIO: no dependency, JPEG, PNG, GIF and BMP in.
 *
 * <p>Decoding is the type check: a file that is not one of those images has no reader, whatever its
 * name or declared type says. The size is read from the header before the pixels, so a small file
 * claiming a huge image is refused without being decoded into memory. What comes out is drawn anew
 * on a blank canvas and encoded as JPEG, which carries none of the original's metadata.
 *
 * <p>ImageIO blocks: the work runs on boundedElastic, off the event loop.
 */
@Component
public class ImageIoPictureNormalizerAdapter implements PictureNormalizer {

    static final int SIZE = 512;
    static final int MAX_SOURCE_SIDE = 4000;
    static final float QUALITY = 0.85f;

    @Override
    public Mono<byte[]> normalize(byte[] upload) {
        return Mono.fromCallable(() -> encode(squared(read(upload))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public String contentType() {
        return "image/jpeg";
    }

    private static BufferedImage read(byte[] upload) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(upload))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);
            if (readers == null || !readers.hasNext()) {
                throw invalid();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (reader.getWidth(0) > MAX_SOURCE_SIDE || reader.getHeight(0) > MAX_SOURCE_SIDE) {
                    throw invalid();
                }
                return reader.read(0);
            } catch (IOException | RuntimeException unreadable) {
                if (unreadable instanceof BusinessException business) {
                    throw business;
                }
                throw invalid();
            } finally {
                reader.dispose();
            }
        }
    }

    /** The centered square, scaled to SIZE, on an opaque canvas: JPEG has no transparency. */
    private static BufferedImage squared(BufferedImage source) {
        int side = Math.min(source.getWidth(), source.getHeight());
        int x = (source.getWidth() - side) / 2;
        int y = (source.getHeight() - side) / 2;

        BufferedImage target = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, SIZE, SIZE);
            graphics.drawImage(source, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
        } finally {
            graphics.dispose();
        }
        return target;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(output);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static BusinessException invalid() {
        return new BusinessException(BusinessErrorCode.PICTURE_INVALID);
    }
}
