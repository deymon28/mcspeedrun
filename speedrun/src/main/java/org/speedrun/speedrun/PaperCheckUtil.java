package org.speedrun.speedrun;

public class PaperCheckUtil {
    private static boolean isPaper() {
        try {
            // Class.forName() бросит исключение, если класс не найден
            Class.forName("com.destroystokyo.paper.PaperConfig");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }


    public static boolean getIsPaper() {return isPaper();}
}
