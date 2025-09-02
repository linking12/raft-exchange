package exchange.core2.core.event;

import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Slf4j
public class SimpleEventsProcessor4Test extends SimpleEventsProcessor {

    private IEventsHandler4Test eventsHandler;

    public SimpleEventsProcessor4Test(IEventsHandler4Test eventsHandler) {
        super(eventsHandler, eventsHandler);
        this.eventsHandler = eventsHandler;

        setSymbolSpecificationProvider(new SymbolSpecificationProvider() {
            @Override
            public CoreSymbolSpecification getSymbolSpecification(int symbol) {
                //todo provide symbol specification
                return new CoreSymbolSpecification(symbol, SymbolType.CURRENCY_EXCHANGE_PAIR, 1, 2, 1000, 1000,
                        0, 0, 0, 0, 0, null, 0, null);
            }
        });

        setUserProfileService(new UserProfileService() {
            @Override
            public UserProfile getUserProfile(long uid) {
                //todo provide userInfo and position
                return super.getUserProfile(uid);
            }
        });
    }

}
