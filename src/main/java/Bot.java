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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class Bot extends TelegramLongPollingBot {

    private final String botUsername;
    private final String botToken;

    private final ForkJoinPool pool = new ForkJoinPool();

    private final Map<String, ImageResizerRecursiveTask> chatsWithWorkingTasks = new HashMap<>();

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
                        sendBackImages(chatId);
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
                        log.warn("unknown command: " + message.toString());
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
                    log.debug("task created for chat " + chatId);
                    sendBackImages(chatId);
                    log.debug("done with chat " + chatId);
                } else {
                    addImageToTask(chatId, sourceImage);
                    log.debug("image added for chat " + chatId);
                }
                return;
            }

            reply(chatId, "Это я не умею. /help");

        } catch (TelegramApiException | IOException | ExecutionException | InterruptedException e) {
            log.error(e.getMessage(), e);
            reply(chatId, "Я немного сломался, попробуй что-нибудь ещё сделать.");
        } catch (IllegalArgumentException e) {
            reply(chatId, "Ошибка в параметрах для масштабирования /help");
        }

    }

    private void addImageToTask(String chatId, Document sourceImage) throws IOException {
        ImageResizerRecursiveTask task = chatsWithWorkingTasks.get(chatId);
        DocumentToProcess documentToAdd = new DocumentToProcess(sourceImage, chatId);
        task.addImage(documentToAdd);
    }

    private File downloadDocument(Document doc) throws TelegramApiException {
        GetFile request = GetFile.builder().fileId(doc.getFileId()).build();
        String path = execute(request).getFilePath();
        return downloadFile(path);
    }

    private void sendBackImages(String chatId) throws TelegramApiException, IOException, ExecutionException, InterruptedException {
        ImageResizerRecursiveTask task = chatsWithWorkingTasks.get(chatId);
        log.debug("task launching for chat " + chatId);
        pool.execute(task);
        log.debug("ready to get results for chat " + chatId);
        //List<File> imagesToSend = task.get();
        log.debug("preparing to send images back to chat " + chatId);
//        for (File f : imagesToSend) {
//            execute(SendDocument.builder()
//                    .caption("Готово")
//                    .chatId(chatId)
//                    .document(new InputFile(f))
//                    .build());
//        }
//        imagesToSend.forEach(File::delete);
//        Files.deleteIfExists(Path.of(chatId));
//        chatsWithWorkingTasks.remove(chatId);
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
        chatsWithWorkingTasks.put(chatId, new ImageResizerRecursiveTask(scalingOptions, chatId));
    }

    public class ImageResizerRecursiveTask extends RecursiveTask<List<File>> {

        private static final int THRESHOLD = 1;
        private final List<DocumentToProcess> documentsToProcess;

        @Getter
        @Setter
        private String[] scalingOptions;
        @Getter
        @Setter
        private String chatId;



        public ImageResizerRecursiveTask(List<DocumentToProcess> documentsToProcess, String[] scalingOptions, String chatId) {
            this.documentsToProcess = Collections.synchronizedList(documentsToProcess);
            this.scalingOptions = scalingOptions;
            this.chatId = chatId;
        }

        public ImageResizerRecursiveTask(String[] scalingOptions, String chatId) throws IOException {
            this(Collections.synchronizedList(new ArrayList<>()), scalingOptions, chatId);
//            Path chatDir = Path.of(chatId);
//            if (!Files.exists(chatDir)) {
//                Files.createDirectory(chatDir);
//            }
        }

        public void addImage(DocumentToProcess document) {
            documentsToProcess.add(document);
        }


        @Override
        protected List<File> compute() {
            log.debug("entering fork");
            if (documentsToProcess.size() > THRESHOLD) {
                log.debug("over threshold -- splitting");
                return invokeAll(createSubTasks()).stream()
                        .map(ForkJoinTask::join)
                        .flatMap(List::stream)
                        .collect(Collectors.toList());
            } else {
                log.debug("running task");
                return documentsToProcess.stream()
                        .map(this::resizeImage)
                        .collect(Collectors.toList());
            }
        }

        private File resizeImage(DocumentToProcess source) {
            OutputStream result = null;
            String fileName = null;
            try {
                Document doc = source.getOriginal();
                fileName = doc.getFileName();
                log.debug("downloading " + fileName);
                String format = doc.getMimeType().split("/")[1];
                int newWidth = 0, newHeight = 0;

                BufferedImage beforeResize = ImageIO.read(downloadDocument(doc));

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
                        .chatId(source.getChatId())
                        .caption("Готово!")
                        .document(new InputFile(inputStream, newFileName))
                        .build());
                System.out.println("sent " + newFileName);
                documentsToProcess.remove(source);
                log.debug("resized " + fileName + ", leaving");
                if(documentsToProcess.size() == 0) {
                    chatsWithWorkingTasks.remove(chatId);
                    log.debug("resized " + fileName + ", leaving");
                    System.out.println("done with " + newFileName);
                }
                System.out.println("done with " + newFileName);
            } catch (IOException | TelegramApiException e) {
                log.error(e.getMessage(), e);
            }

            return null;
        }

        private Collection<ImageResizerRecursiveTask> createSubTasks() {
            int length = documentsToProcess.size();
            List<ImageResizerRecursiveTask> splitTasks = new ArrayList<>();
            for (int i = 0; ; i += THRESHOLD) {
                if (i + THRESHOLD >= length) {
                    splitTasks.add(new ImageResizerRecursiveTask(documentsToProcess.subList(i, length), scalingOptions, chatId));
                    break;
                }
                splitTasks.add(new ImageResizerRecursiveTask(documentsToProcess.subList(i, i + THRESHOLD), scalingOptions, chatId));
            }
            return splitTasks;
        }
    }
}