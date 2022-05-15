import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.objects.Document;

@Data
@Slf4j
@AllArgsConstructor
public class DocumentToProcess {
    private Document original;
    private String chatId;
}
