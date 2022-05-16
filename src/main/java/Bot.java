import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.imgscalr.Scalr;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.*;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//TODO: scaling optimization (memory usage, speed secondary), new tests, cleanup and refactoring
@Slf4j
public class Bot extends TelegramLongPollingBot {

    private final String botUsername;
    private final String botToken;

    private final ForkJoinPool pool = new ForkJoinPool();

    private final Map<String, ImageResizerRecursiveAction> chatsWithWorkingTasks = new HashMap<>();

    private final Pattern scalingPattern = Pattern.compile("((\\d+( \\d+)?)|(\\d+)%)$");

    private static String instructions;

    static {
        try {
            instructions = new String(Bot.class.getClassLoader().getResourceAsStream("data/instructions").readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    public Bot(String botUsername, String botToken) {
        this.botUsername = botUsername;
        this.botToken = botToken;
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {

        if (!update.hasMessage()) {
            log.warn("no message received");
            return;
        }

        Message message = update.getMessage();
        String chatId = String.valueOf(message.getChatId());

        try {
            if (message.hasText()) {
                String command = message.getText();
                switch (command) {
                    case "/start":
                    case "/help":
                        reply(chatId, instructions);
                        break;
                    case "/processBatch":
                        reply(chatId, "Запустил обработку. Подожди немного.");
                        pool.execute(chatsWithWorkingTasks.get(chatId));
                        break;
                    default:
                        if (command.startsWith("/batch ")) {
                            command = command.replace("/batch ", "").trim();
                            Matcher matcher = scalingPattern.matcher(command);
                            if (matcher.matches()) {
                                String[] scalingOptions = matcher.group(1).split(" ");
                                addTask(chatId, scalingOptions);
                                break;
                            }
                        }
                        log.warn("unknown command: " + message);
                        reply(chatId, "Я не знаю, что с этим делать. Для подсказки пришли команду /help");
                        break;
                }
                return;
            }

            if (message.hasDocument()) {
                Document sourceImage = message.getDocument();
                if (!sourceImage.getMimeType().contains("image")) {
                    log.warn("unsupported mime type: " + sourceImage.getMimeType());
                    reply(chatId, "Я не умею работать с " + sourceImage.getMimeType());
                    return;
                }

                String caption = message.getCaption();
                if (!chatsWithWorkingTasks.containsKey(chatId) && caption == null) {
                    reply(chatId, "Необходимо указать параметры обработки, для помощи используй /help");
                    return;
                }

                if (!chatsWithWorkingTasks.containsKey(chatId)) {
                    Matcher matcher = scalingPattern.matcher(caption);
                    if (!matcher.matches()) {
                        throw new IllegalArgumentException("слишком много параметров");
                    }
                    String[] scalingOptions = caption.split(" ");
                    addTask(chatId, scalingOptions);
                    addImageToTask(chatId, sourceImage);
                    pool.execute(chatsWithWorkingTasks.get(chatId));
                } else {
                    addImageToTask(chatId, sourceImage);
                }
                return;
            }

            reply(chatId, "Это я не умею. /help");

        } catch (IOException e) {
            log.error(e.getMessage(), e);
            reply(chatId, "Я немного сломался, попробуй что-нибудь ещё сделать.");
        } catch (IllegalArgumentException e) {
            reply(chatId, "Ошибка в параметрах для масштабирования /help");
        }
    }

    private void addImageToTask(String chatId, Document sourceImage) throws IOException {
        ImageResizerRecursiveAction task = chatsWithWorkingTasks.get(chatId);
        task.addDocument(sourceImage);
    }

    private File downloadDocument(Document doc) throws TelegramApiException {
        GetFile request = GetFile.builder().fileId(doc.getFileId()).build();
        String path = execute(request).getFilePath();
        return downloadFile(path);
    }

    private void reply(String chatId, String text) {
        try {
            execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .build());
        } catch (TelegramApiException e) {
            log.error(e.getMessage(), e);
        }

    }

    private void addTask(String chatId, String[] scalingOptions) throws IllegalArgumentException, IOException {
        if (chatsWithWorkingTasks.containsKey(chatId)) {
            throw new IllegalArgumentException("Задача для чата " + chatId + " уже создана");
        }
        chatsWithWorkingTasks.put(chatId, new ImageResizerRecursiveAction(scalingOptions, chatId));
    }

    private class ImageResizerRecursiveAction extends RecursiveAction {
        private final List<Document> documentsToProcess;

        @Getter
        private final AtomicInteger totalImages;
        @Getter
        @Setter
        private String[] scalingOptions;
        @Getter
        @Setter
        private String chatId;

        public ImageResizerRecursiveAction(List<Document> documentsToProcess, String[] scalingOptions, String chatId, AtomicInteger totalImages) {
            this.documentsToProcess = documentsToProcess;
            this.totalImages = totalImages;
            this.scalingOptions = scalingOptions;
            this.chatId = chatId;
        }

        public ImageResizerRecursiveAction(String[] scalingOptions, String chatId) {
            this(new ArrayList<>(), scalingOptions, chatId, new AtomicInteger());
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
                documentsToProcess.forEach(this::resizeImage);
            }
        }

        private void resizeImage(Document source) {
            try {
                String fileName = source.getFileName();
                String format = source.getMimeType().split("/")[1];

                BufferedImage beforeResize = ImageIO.read(downloadDocument(source));
                int[] newDimensions = calculateNewDimensions(beforeResize.getWidth(), beforeResize.getHeight(), scalingOptions);
                int newWidth = newDimensions[0];
                int newHeight = newDimensions[1];
                String newFileName = fileName.split("\\.")[0] + "_" + newWidth + "x" + newHeight + "." + fileName.split("\\.")[1];

                BufferedImage resizedImage = Scalr.resize(beforeResize, Scalr.Method.ULTRA_QUALITY, Scalr.Mode.FIT_EXACT,
                        newWidth, newHeight, Scalr.OP_ANTIALIAS);

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                ImageIO.write(resizedImage, format, outputStream);
                InputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());

                execute(SendDocument.builder()
                        .chatId(chatId)
                        .caption("Готово!")
                        .document(new InputFile(inputStream, newFileName))
                        .build());

                outputStream.close();
                inputStream.close();
                totalImages.decrementAndGet();
                if(totalImages.get() == 0) {
                    chatsWithWorkingTasks.remove(chatId);
                    reply(chatId, "Обработка закончена.");
                }
            } catch (IOException | TelegramApiException e) {
                log.error(e.getMessage(), e);
                reply(chatId, "Я немного сломался, попробуй что-нибудь ещё сделать.");
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
            return  newDimensions;
        }

        private Collection<ImageResizerRecursiveAction> createSubTasks() {
            List<ImageResizerRecursiveAction> splitTasks = new ArrayList<>();
            documentsToProcess.forEach(document -> splitTasks.add(new ImageResizerRecursiveAction(new ArrayList<>() {{
                add(document);
            }}, scalingOptions, chatId, totalImages)));
            return splitTasks;
        }
    }
}