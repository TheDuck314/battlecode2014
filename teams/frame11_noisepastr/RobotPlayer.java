package frame11_noisepastr;

import battlecode.common.*;

public class RobotPlayer {
	public static void run(RobotController theRC) throws Exception {
		Bot bot = null;

		switch (theRC.getType()) {
			case HQ:
				bot = new BotHQ(theRC);
				break;
			case SOLDIER:
				bot = new BotSoldier(theRC);
				break;
			case PASTR:
				bot = new BotPastr(theRC);
				break;
			case NOISETOWER:
				bot = new BotNoiseTower(theRC);
				break;
			default:
				throw new Exception("Unknown robot type!!! :(");
		}

		bot.loop();
	}
}
