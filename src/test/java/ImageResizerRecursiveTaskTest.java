import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;


@DisplayName("Tests different image resizer task methods")
public class ImageResizerRecursiveTaskTest {
    //input 100x100 file
    private final File input = new File("src/test/resources/image/input.png");

    @Test
    @DisplayName("Test that image is resized correctly")
    public void testResizeImage() throws IOException, ExecutionException, InterruptedException {
        String chatId = "1";
        ImageResizerRecursiveTask task = new ImageResizerRecursiveTask(new String[]{"50%"}, chatId);
        task.addImage(new ImageToResize(task.getScalingOptions(), input, "input.png", "png"));
        ForkJoinPool pool = new ForkJoinPool();
        pool.execute(task);
        List<File> output = task.get();
        BufferedImage etalon = ImageIO.read(new File("src/test/resources/image/etalon.png"));
        BufferedImage result = ImageIO.read(new File(chatId + "/input_50x50.png"));
        byte[] expected = ((DataBufferByte) etalon.getData().getDataBuffer()).getData();
        byte[] actual = ((DataBufferByte) result.getData().getDataBuffer()).getData();

        output.get(0).delete();
        Files.deleteIfExists(Path.of(chatId));

        Assertions.assertArrayEquals(expected, actual);
    }
}
