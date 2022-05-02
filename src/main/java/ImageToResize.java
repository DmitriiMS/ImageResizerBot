import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

@Data
@Slf4j
public class ImageToResize {
    private BufferedImage original;
    private int newWidth;
    private int newHeight;
    private String fileName;
    private String format;

    public ImageToResize(String[] scalingOptions, File input, String fileName, String format) throws IllegalArgumentException, IOException {
        log.debug("preparing image to resize");
        original = ImageIO.read(input);
        this.fileName = fileName;
        this.format = format;
        if (scalingOptions.length == 2) {
            newWidth = Integer.parseInt(scalingOptions[0]);
            newHeight = Integer.parseInt(scalingOptions[1]);
        } else if (scalingOptions.length == 1) {
            if (scalingOptions[0].contains("%")) {
                int scale = Integer.parseInt(scalingOptions[0].replaceAll("\\D", ""));
                newWidth = (int) (original.getWidth() * (scale / 100.));
                newHeight = (int) (original.getHeight() * (scale / 100.));
            } else {
                newWidth = Integer.parseInt(scalingOptions[0].replaceAll("\\D", ""));
                newHeight = (int) (((double) newWidth / original.getWidth()) * (double) original.getHeight());
            }
        } else {
            throw new IllegalArgumentException("слишком много параметров");
        }
        log.debug("new image to resize " + fileName + " " + format + " " + newWidth + " " + newHeight);
    }
}
