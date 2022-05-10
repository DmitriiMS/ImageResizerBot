import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Tests correct calculation of new width and height")
public class ImageToResizeTest {
    //input 100x100 file
    private final File input = new File("src/test/resources/image/input.png");

    @Test
    @DisplayName("Test if percentage scaling option works correctly")
    public void testPercentageParameter() throws IOException {
        ImageToResize imageToResize = new ImageToResize(new String[]{"50%"}, input, "input.png", "png");
        Assertions.assertAll("Should correctly halve width and height, and have correct name and format",
                () -> assertEquals(50, imageToResize.getNewWidth()),
                () -> assertEquals(50, imageToResize.getNewHeight()),
                () -> assertEquals("input.png", imageToResize.getFileName()),
                () -> assertEquals("png", imageToResize.getFormat()));
    }

    @Test
    @DisplayName("Test if only width scaling option works correctly")
    public void testWidthParameter() throws IOException {
        ImageToResize imageToResize = new ImageToResize(new String[]{"20"}, input, "input.png", "png");
        Assertions.assertAll("Should correctly calculate width and height, and have correct name and format",
                () -> assertEquals(20, imageToResize.getNewWidth()),
                () -> assertEquals(20, imageToResize.getNewHeight()),
                () -> assertEquals("input.png", imageToResize.getFileName()),
                () -> assertEquals("png", imageToResize.getFormat()));
    }

    @Test
    @DisplayName("Test if width and height scaling options work correctly")
    public void testWidthHeightParameter() throws IOException {
        ImageToResize imageToResize = new ImageToResize(new String[]{"10", "15"}, input, "input.png", "png");
        Assertions.assertAll("Should correctly set width and height, and have correct name and format",
                () -> assertEquals(10, imageToResize.getNewWidth()),
                () -> assertEquals(15, imageToResize.getNewHeight()),
                () -> assertEquals("input.png", imageToResize.getFileName()),
                () -> assertEquals("png", imageToResize.getFormat()));
    }
}
