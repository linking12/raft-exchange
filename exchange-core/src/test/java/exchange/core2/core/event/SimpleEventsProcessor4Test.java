package exchange.core2.core.event;

import exchange.core2.core.SimpleEventsProcessor;
import exchange.core2.core.common.CoreSymbolSpecification;
import exchange.core2.core.common.SymbolType;
import exchange.core2.core.common.UserProfile;
import exchange.core2.core.common.UserStatus;
import exchange.core2.core.processors.SymbolSpecificationProvider;
import exchange.core2.core.processors.UserProfileService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import static exchange.core2.tests.util.TestConstants.ALL_USERS;

@Getter
@Slf4j
public class SimpleEventsProcessor4Test extends SimpleEventsProcessor {

    private final IEventsHandler4Test eventsHandler;

    private final Map<Integer, CoreSymbolSpecification> symbolSpecificationMap;
    private final Map<Long, UserProfile> userProfileMap;

    public SimpleEventsProcessor4Test(IEventsHandler4Test eventsHandler) {
        super(eventsHandler, eventsHandler);
        this.eventsHandler = eventsHandler;

        this.symbolSpecificationMap = new HashMap<>();
        this.userProfileMap = new HashMap<>();
        ALL_USERS.forEach(e -> userProfileMap.put(e, new UserProfile(e, UserStatus.valueOf("ACTIVE"))));
        setSymbolSpecificationProvider(null);
        setUserProfileService(0, null);
    }

    public SimpleEventsProcessor4Test(IEventsHandler4Test eventsHandler, Boolean isPerfTest) {
        super(eventsHandler, eventsHandler);
        this.eventsHandler = eventsHandler;

        this.symbolSpecificationMap = new HashMap<>();
        this.userProfileMap = new HashMap<>();
    }

    @Override
    public void setSymbolSpecificationProvider(SymbolSpecificationProvider symbolSpecificationProvider) {
        super.setSymbolSpecificationProvider(new SymbolSpecificationProvider() {
            @Override
            public CoreSymbolSpecification getSymbolSpecification(int symbol) {
                if (symbolSpecificationMap.isEmpty()) {
                    return symbolSpecificationProvider.getSymbolSpecification(symbol);
                }
                return symbolSpecificationMap.get(symbol);
            }
        });
    }

    @Override
    public void setUserProfileService(int shardId, UserProfileService userProfileService) {
        super.setUserProfileService(shardId, new UserProfileService() {
            @Override
            public UserProfile getUserProfile(long uid) {
                if (userProfileMap.isEmpty()) {
                    return userProfileService.getUserProfile(uid);
                }
                return userProfileMap.get(uid);
            }
        });
    }

    public CoreSymbolSpecification fakeSpotSymbol(int symbol) {
        return new CoreSymbolSpecification(symbol, SymbolType.CURRENCY_EXCHANGE_PAIR, 1, 2, 1000, 1000,
                0, 0, 0, 0, 0, null, 0, null);
    }

}
