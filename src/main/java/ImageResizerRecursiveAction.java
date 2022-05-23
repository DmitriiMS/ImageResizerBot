import lombok.extern.slf4j.Slf4j;
import org.imgscalr.Scalr;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.RecursiveAction;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
public class ImageResizerRecursiveAction extends RecursiveAction {
    private final Bot runningBot;
    private final List<Document> documentsToProcess;
    private final AtomicInteger totalImages;
    private final String[] scalingOptions;
    private final String chatId;

    public ImageResizerRecursiveAction(Bot runningBot, List<Document> documentsToProcess, String[] scalingOptions, String chatId, AtomicInteger totalImages) {
        this.runningBot = runningBot;
        this.documentsToProcess = documentsToProcess;
        this.totalImages = totalImages;
        this.scalingOptions = scalingOptions;
        this.chatId = chatId;
    }

    public ImageResizerRecursiveAction(Bot runningBot, String[] scalingOptions, String chatId) {
        this(runningBot, new ArrayList<>(), scalingOptions, chatId, new AtomicInteger());
    }

    public void addDocument(Document document) {
        documentsToProcess.add(document);
        totalImages.incrementAndGet();
    }

    @Override
    protected void compute() {
        if (documentsToProcess.size() > 1) {
            invokeAll(createSubTasks());
        } else {
            processImageAndSendResult(documentsToProcess.get(0));
        }
    }

    private void processImageAndSendResult(Document source) {
        try {
            String fileName = source.getFileName();
            String format = source.getMimeType().split("/")[1];

            InputStream streamedDocument = runningBot.downloadDocument(source);
            BufferedImage beforeResize = ImageIO.read(streamedDocument);
            streamedDocument.close();
            int[] newWidthAndHeight = calculateNewDimensions(beforeResize.getWidth(), beforeResize.getHeight(), scalingOptions);
            String newFileName = fileName.split("\\.")[0] + "_" + newWidthAndHeight[0] + "x" + newWidthAndHeight[1] +
                    "." + fileName.split("\\.")[1];

            InputStream outgoingStream = resizeImage(beforeResize, newWidthAndHeight, format);
            runningBot.execute(SendDocument.builder()
                    .chatId(chatId)
                    .caption("Готово!")
                    .document(new InputFile(outgoingStream, newFileName))
                    .build());
            outgoingStream.close();

            if (totalImages.decrementAndGet() == 0) {
                runningBot.getChatsWithWorkingTasks().remove(chatId);
                runningBot.reply(chatId, "Обработка закончена.");
            }
        } catch (IOException | TelegramApiException e) {
            log.error(e.getMessage(), e);
            runningBot.reply(chatId, "Я немного сломался, попробуй что-нибудь ещё сделать.");
        }
    }

    private int[] calculateNewDimensions(int currentWidth, int currentHeight, String[] scalingOptions) {
        int[] newWidthAndHeight = new int[2];
        if (scalingOptions.length == 2) {
            newWidthAndHeight[0] = Integer.parseInt(scalingOptions[0]);
            newWidthAndHeight[1] = Integer.parseInt(scalingOptions[1]);
        } else if (scalingOptions.length == 1) {
            if (scalingOptions[0].contains("%")) {
                int scale = Integer.parseInt(scalingOptions[0].replaceAll("\\D", ""));
                newWidthAndHeight[0] = (int) (currentWidth * (scale / 100.));
                newWidthAndHeight[1] = (int) (currentHeight * (scale / 100.));
            } else {
                newWidthAndHeight[0] = Integer.parseInt(scalingOptions[0].replaceAll("\\D", ""));
                newWidthAndHeight[1] = (int) (((double) newWidthAndHeight[0] / currentWidth) * (double) currentHeight);
            }
        }
        return newWidthAndHeight;
    }

    InputStream resizeImage(BufferedImage beforeResize, int[] newWidthAndHeight, String format) throws IOException {
        BufferedImage resizedImage = Scalr.resize(beforeResize, Scalr.Method.ULTRA_QUALITY, Scalr.Mode.FIT_EXACT,
                newWidthAndHeight[0], newWidthAndHeight[1], Scalr.OP_ANTIALIAS);
        ByteArrayOutputStream bufferStream = new ByteArrayOutputStream();
        ImageIO.write(resizedImage, format, bufferStream);
        bufferStream.close();
        return new ByteArrayInputStream(bufferStream.toByteArray());
    }

    private Collection<ImageResizerRecursiveAction> createSubTasks() {
        return documentsToProcess.stream()
                .map(document ->
                        new ImageResizerRecursiveAction(runningBot, new ArrayList<>() {{ add(document); }},
                                scalingOptions, chatId, totalImages))
                .collect(Collectors.toList());
    }
}
