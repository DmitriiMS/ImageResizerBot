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

//TODO: move code to methods, look into moving methods to utils class, cleanup and optimization
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
                        log.debug("task launching for chat " + chatId);
                        pool.execute(chatsWithWorkingTasks.get(chatId));
                        log.debug("done with chat " + chatId);
                        break;
                    default:
                        if (command.startsWith("/batch ")) {
                            command = command.replace("/batch ", "").trim();
                            Matcher matcher = scalingPattern.matcher(command);
                            if (matcher.matches()) {
                                String[] scalingOptions = matcher.group(1).split(" ");
                                addTask(chatId, scalingOptions);
                                log.debug("created batch task for chat " + chatId);
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

                log.debug("received document for processing from chat " + chatId);

                if (!chatsWithWorkingTasks.containsKey(chatId)) {
                    Matcher matcher = scalingPattern.matcher(caption);
                    if (!matcher.matches()) {
                        throw new IllegalArgumentException("слишком много параметров");
                    }
                    String[] scalingOptions = caption.split(" ");
                    addTask(chatId, scalingOptions);
                    addImageToTask(chatId, sourceImage);
                    log.debug("task created and launching for chat " + chatId);
                    pool.execute(chatsWithWorkingTasks.get(chatId));
                    log.debug("done with chat " + chatId);
                } else {
                    addImageToTask(chatId, sourceImage);
                    log.debug("image added for chat " + chatId);
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

    public class ImageResizerRecursiveAction extends RecursiveAction {
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
            log.debug("entering fork");
            log.debug("at the start total images: " + totalImages.get());
            if (documentsToProcess.size() > 1) {
                log.debug("over threshold -- splitting");
                invokeAll(createSubTasks());
            } else {
                log.debug("running task");
                documentsToProcess.forEach(this::resizeImage);
            }
        }

        private void resizeImage(Document source) {
            try {
                String fileName = source.getFileName();
                log.debug("downloading " + fileName);
                String format = source.getMimeType().split("/")[1];
                int newWidth = 0, newHeight = 0;

                BufferedImage beforeResize = ImageIO.read(downloadDocument(source));

                if (scalingOptions.length == 2) {
                    newWidth = Integer.parseInt(scalingOptions[0]);
                    newHeight = Integer.parseInt(scalingOptions[1]);
                } else if (scalingOptions.length == 1) {
                    if (scalingOptions[0].contains("%")) {
                        int scale = Integer.parseInt(scalingOptions[0].replaceAll("\\D", ""));
                        newWidth = (int) (beforeResize.getWidth() * (scale / 100.));
                        newHeight = (int) (beforeResize.getHeight() * (scale / 100.));
                    } else {
                        newWidth = Integer.parseInt(scalingOptions[0].replaceAll("\\D", ""));
                        newHeight = (int) (((double) newWidth / beforeResize.getWidth()) * (double) beforeResize.getHeight());
                    }
                }

                log.debug("starting to resize " + fileName);
                BufferedImage resizedImage = Scalr.resize(beforeResize, Scalr.Method.ULTRA_QUALITY, Scalr.Mode.FIT_EXACT,
                        newWidth, newHeight, Scalr.OP_ANTIALIAS);
                String newFileName = fileName.split("\\.")[0] + "_" +
                        newWidth + "x" + newHeight + "." +
                        fileName.split("\\.")[1];

                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                ImageIO.write(resizedImage, format, outputStream);
                InputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());

                log.debug("sending " + fileName + " to chat " + chatId);
                execute(SendDocument.builder()
                        .chatId(chatId)
                        .caption("Готово!")
                        .document(new InputFile(inputStream, newFileName))
                        .build());
                outputStream.close();
                inputStream.close();
                totalImages.decrementAndGet();
                log.debug("total images remaining: " + totalImages.get());
                log.debug("resized " + fileName + ", leaving");
                if(totalImages.get() == 0) {
                    chatsWithWorkingTasks.remove(chatId);
                    log.debug("removed last image, deleting task from map");
                }
            } catch (IOException | TelegramApiException e) {
                log.error(e.getMessage(), e);
                reply(chatId, "Я немного сломался, попробуй что-нибудь ещё сделать.");
            }

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