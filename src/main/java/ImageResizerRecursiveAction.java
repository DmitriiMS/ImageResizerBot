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
            int[] newDimensions = calculateNewDimensions(beforeResize.getWidth(), beforeResize.getHeight(), scalingOptions);
            int newWidth = newDimensions[0];
            int newHeight = newDimensions[1];
            String newFileName = fileName.split("\\.")[0] + "_" + newWidth + "x" + newHeight + "." + fileName.split("\\.")[1];

            BufferedImage resizedImage = Scalr.resize(beforeResize, Scalr.Method.ULTRA_QUALITY, Scalr.Mode.FIT_EXACT,
                    newWidth, newHeight, Scalr.OP_ANTIALIAS);

            ByteArrayOutputStream bufferStream = new ByteArrayOutputStream();
            ImageIO.write(resizedImage, format, bufferStream);
            InputStream outgoingStream = new ByteArrayInputStream(bufferStream.toByteArray());

            runningBot.execute(SendDocument.builder()
                    .chatId(chatId)
                    .caption("Готово!")
                    .document(new InputFile(outgoingStream, newFileName))
                    .build());

            bufferStream.close();
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
        int[] newDimensions = new int[2];
        if (scalingOptions.length == 2) {
            newDimensions[0] = Integer.parseInt(scalingOptions[0]);
            newDimensions[1] = Integer.parseInt(scalingOptions[1]);
        } else if (scalingOptions.length == 1) {
            if (scalingOptions[0].contains("%")) {
                int scale = Integer.parseInt(scalingOptions[0].replaceAll("\\D", ""));
                newDimensions[0] = (int) (currentWidth * (scale / 100.));
                newDimensions[1] = (int) (currentHeight * (scale / 100.));
            } else {
                newDimensions[0] = Integer.parseInt(scalingOptions[0].replaceAll("\\D", ""));
                newDimensions[1] = (int) (((double) newDimensions[0] / currentWidth) * (double) currentHeight);
            }
        }
        return newDimensions;
    }

    private Collection<ImageResizerRecursiveAction> createSubTasks() {
        List<ImageResizerRecursiveAction> splitTasks = new ArrayList<>();
        documentsToProcess.forEach(document -> splitTasks.add(new ImageResizerRecursiveAction(runningBot, new ArrayList<>() {{
            add(document);
        }}, scalingOptions, chatId, totalImages)));
        return splitTasks;
    }
}

