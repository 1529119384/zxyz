package uno.acloud.common.util;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TransactionHelper {

    private final TransactionTemplate transactionTemplate;

    public TransactionHelper(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    public <T> T execute(TransactionCallback<T> action) {
        return transactionTemplate.execute(action::doInTransaction);
    }

    public void executeWithoutResult(TransactionVoidCallback action) {
        transactionTemplate.executeWithoutResult(status -> action.doInTransaction(status));
    }

    @FunctionalInterface
    public interface TransactionCallback<T> {
        T doInTransaction(org.springframework.transaction.TransactionStatus status);
    }

    @FunctionalInterface
    public interface TransactionVoidCallback {
        void doInTransaction(org.springframework.transaction.TransactionStatus status);
    }
}
