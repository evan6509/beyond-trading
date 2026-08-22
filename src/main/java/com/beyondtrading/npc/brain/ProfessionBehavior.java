package com.beyondtrading.npc.brain;

import com.beyondtrading.entity.AINPCEntity;
import com.beyondtrading.npc.data.Profession;

public interface ProfessionBehavior {
    int getWorkDurationTicks(AINPCEntity npc);

    static ProfessionBehavior forProfession(Profession profession) {
        return switch (profession) {
            case FARMER -> FarmerBehavior.INSTANCE;
            case BLACKSMITH -> BlacksmithBehavior.INSTANCE;
            case LIBRARIAN -> LibrarianBehavior.INSTANCE;
            case EXPLORER -> ExplorerBehavior.INSTANCE;
            case MERCHANT -> MerchantBehavior.INSTANCE;
            default -> DefaultBehavior.INSTANCE;
        };
    }

    class DefaultBehavior implements ProfessionBehavior {
        static final ProfessionBehavior INSTANCE = new DefaultBehavior();
        @Override public int getWorkDurationTicks(AINPCEntity npc) { return 200; }
    }

    class FarmerBehavior implements ProfessionBehavior {
        static final ProfessionBehavior INSTANCE = new FarmerBehavior();
        @Override public int getWorkDurationTicks(AINPCEntity npc) { return 800; }
    }

    class BlacksmithBehavior implements ProfessionBehavior {
        static final ProfessionBehavior INSTANCE = new BlacksmithBehavior();
        @Override public int getWorkDurationTicks(AINPCEntity npc) { return 600; }
    }

    class LibrarianBehavior implements ProfessionBehavior {
        static final ProfessionBehavior INSTANCE = new LibrarianBehavior();
        @Override public int getWorkDurationTicks(AINPCEntity npc) { return 700; }
    }

    class ExplorerBehavior implements ProfessionBehavior {
        static final ProfessionBehavior INSTANCE = new ExplorerBehavior();
        @Override public int getWorkDurationTicks(AINPCEntity npc) { return 300; }
    }

    class MerchantBehavior implements ProfessionBehavior {
        static final ProfessionBehavior INSTANCE = new MerchantBehavior();
        @Override public int getWorkDurationTicks(AINPCEntity npc) { return 500; }
    }
}
