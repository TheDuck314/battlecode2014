package anatid21_noisepastr_stonewall;

import battlecode.common.*;

public class RobotPlayer {
	public static void run(RobotController theRC) throws Exception {
		switch (theRC.getType()) {
			case HQ:
				BotHQ.loop(theRC);
				break;
			case SOLDIER:
				BotSoldier.loop(theRC);
				break;
			case PASTR:
				BotPastr.loop(theRC);
				break;
			case NOISETOWER:
				BotNoiseTower.loop(theRC);
				break;
			default:
				throw new Exception("Unknown robot type!!! :(");
		}
	}
}
