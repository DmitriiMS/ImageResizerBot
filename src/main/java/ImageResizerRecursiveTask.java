import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.imgscalr.Scalr;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveTask;
import java.util.stream.Collectors;

@Slf4j
public class ImageResizerRecursiveTask extends RecursiveTask<List<File>> {

    private static final int THRESHOLD = 2;
    private final List<ImageToResize> imagesToProcess;

    @Getter
    @Setter
    String[] scalingOptions;
    @Getter
    @Setter
    String chatId;

    public ImageResizerRecursiveTask (List<ImageToResize> imagesToProcess, String[] scalingOptions, String chatId) {
        this.imagesToProcess = imagesToProcess;
        this.scalingOptions = scalingOptions;
        this.chatId = chatId;
    }

    public ImageResizerRecursiveTask(String[] scalingOptions, String chatId) throws IOException {
        this(new ArrayList<>(), scalingOptions, chatId);
        Path chatDir = Path.of(chatId);
        if(!Files.exists(chatDir)) {
            Files.createDirectory(chatDir);
        }
    }

    public void addImage(ImageToResize image) {
        imagesToProcess.add(image);
    }


    @Override
    protected List<File> compute() {
        log.debug("entering fork");
        if(imagesToProcess.size() > THRESHOLD) {
           log.debug("over threshold -- splitting");
           return invokeAll(createSubTasks()).stream()
                   .map(ForkJoinTask::join)
                   .flatMap(List::stream)
                   .collect(Collectors.toList());
        } else {
            log.debug("running task");
            return imagesToProcess.stream()
                    .map(this::resizeImage)
                    .collect(Collectors.toList());
        }
    }

    private File resizeImage(ImageToResize source){
        log.debug("starting to resize " + source.getFileName());
        BufferedImage resizedImage = Scalr.resize(source.getOriginal(), Scalr.Method.ULTRA_QUALITY, Scalr.Mode.FIT_EXACT,
                source.getNewWidth(), source.getNewHeight(), Scalr.OP_ANTIALIAS);
        String fileName = source.getFileName().split("\\.")[0] + "_" +
                source.getNewWidth() + "x" + source.getNewHeight() + "." +
                source.getFileName().split("\\.")[1];
        File result = new File(chatId, fileName);
        try {
            ImageIO.write(resizedImage, source.getFormat(), result);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        log.debug("resized " + source.getFileName() + ", leaving");
        return result;
    }

    private Collection<ImageResizerRecursiveTask> createSubTasks() {
        int length = imagesToProcess.size();
        List<ImageResizerRecursiveTask> splitTasks = new ArrayList<>();
        for(int i = 0;;i++) {
            if(i + THRESHOLD >= length) {
                splitTasks.add(new ImageResizerRecursiveTask(imagesToProcess.subList(i, length), scalingOptions, chatId));
                break;
            }
            splitTasks.add(new ImageResizerRecursiveTask(imagesToProcess.subList(i, i + THRESHOLD), scalingOptions, chatId));
        }
        return splitTasks;
    }
}
