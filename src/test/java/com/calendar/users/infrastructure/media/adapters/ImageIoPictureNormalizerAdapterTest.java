package com.calendar.users.infrastructure.media.adapters;

import com.calendar.users.exception.BusinessErrorCode;
import com.calendar.users.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ImageIoPictureNormalizerAdapterTest {

    private final ImageIoPictureNormalizerAdapter normalizer = new ImageIoPictureNormalizerAdapter();

    private static byte[] image(int width, int height, String format) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        image.getGraphics().setColor(Color.ORANGE);
        image.getGraphics().fillRect(0, 0, width / 2, height);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, format, bytes);
        return bytes.toByteArray();
    }

    /** A JPEG with an EXIF (APP1) segment inserted right after its start marker, as a phone writes one. */
    private static byte[] jpegWithExif() throws IOException {
        byte[] jpeg = image(800, 600, "jpeg");
        byte[] payload = "Exif\0\0GPS 46.32N 0.46W".getBytes(StandardCharsets.ISO_8859_1);
        int length = payload.length + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        out.write(payload);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private static boolean hasApp1Segment(byte[] jpeg) {
        for (int i = 2; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xff) == 0xFF && (jpeg[i + 1] & 0xff) == 0xE1) {
                return true;
            }
        }
        return false;
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void normalize_shouldGiveASquareJpegOfTheTargetSize() {
        StepVerifier.create(normalizer.normalize(uncheckedImage(1200, 800, "png")))
                .assertNext(jpeg -> {
                    assertThat(jpeg[0] & 0xff).isEqualTo(0xFF);
                    assertThat(jpeg[1] & 0xff).isEqualTo(0xD8);
                    BufferedImage result = uncheckedDecode(jpeg);
                    assertThat(result.getWidth()).isEqualTo(ImageIoPictureNormalizerAdapter.SIZE);
                    assertThat(result.getHeight()).isEqualTo(ImageIoPictureNormalizerAdapter.SIZE);
                })
                .verifyComplete();
        assertThat(normalizer.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    @DisplayName("a phone photo's EXIF block, which can carry where it was taken, does not survive")
    void normalize_shouldDropTheExifMetadata() throws IOException {
        byte[] upload = jpegWithExif();
        assertThat(hasApp1Segment(upload)).isTrue();

        StepVerifier.create(normalizer.normalize(upload))
                .assertNext(jpeg -> assertThat(hasApp1Segment(jpeg)).isFalse())
                .verifyComplete();
    }

    @Test
    void normalize_shouldRefuseAFileThatIsNotAnImage() {
        StepVerifier.create(normalizer.normalize("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)))
                .expectErrorMatches(ImageIoPictureNormalizerAdapterTest::invalid)
                .verify();
    }

    @Test
    void normalize_shouldRefuseATruncatedImage() {
        byte[] png = uncheckedImage(300, 300, "png");
        byte[] truncated = java.util.Arrays.copyOf(png, png.length / 3);

        StepVerifier.create(normalizer.normalize(truncated))
                .expectErrorMatches(ImageIoPictureNormalizerAdapterTest::invalid)
                .verify();
    }

    @Test
    @DisplayName("an image above the size limit is refused from its header, before it is decoded")
    void normalize_shouldRefuseAnImageAboveTheLimit() {
        StepVerifier.create(normalizer.normalize(uncheckedImage(ImageIoPictureNormalizerAdapter.MAX_SOURCE_SIDE + 1, 10, "png")))
                .expectErrorMatches(ImageIoPictureNormalizerAdapterTest::invalid)
                .verify();
    }

    private static boolean invalid(Throwable error) {
        return error instanceof BusinessException business && business.getErrorCode() == BusinessErrorCode.PICTURE_INVALID;
    }

    private static byte[] uncheckedImage(int width, int height, String format) {
        try {
            return image(width, height, format);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BufferedImage uncheckedDecode(byte[] bytes) {
        try {
            return decode(bytes);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
