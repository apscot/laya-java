package io.github.apscot.laya.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LanguageRouterTest {
    @Test
    void routes() {
        assertTrue(LanguageRouter.isEnglish("I was billed twice. Please refund the duplicate."));
        assertTrue(LanguageRouter.isEnglish("Help! My payouts have been failing for 3 days."));
        assertFalse(LanguageRouter.isEnglish("发票被重复扣款，请退款。"));
        assertFalse(LanguageRouter.isEnglish("Привет, как дела?"));
        assertFalse(LanguageRouter.isEnglish("Me cobraron dos veces, por favor devuélvanme el dinero"));
        assertTrue(LanguageRouter.isEnglish("The café was great and the service is fine"));
    }
}
