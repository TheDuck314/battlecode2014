package anatid20_oldmicro;

import battlecode.common.*;

public class BotNoiseTower extends Bot {
	public static void loop(RobotController theRC) throws Exception {
		init(theRC);
		while (true) {
			try {
				turn();
			} catch (Exception e) {
				e.printStackTrace();
			}
			rc.yield();
		}
	}
	
	protected static void init(RobotController theRC) throws GameActionException {
		Bot.init(theRC);
//		Debug.init(theRC, "herd");

		here = rc.getLocation();
		
		// claim the assignment to build this tower so others know not to build it
		int numPastrLocations = MessageBoard.NUM_PASTR_LOCATIONS.readInt();
		for (int i = 0; i < numPastrLocations; i++) {
			MapLocation pastrLoc = MessageBoard.BEST_PASTR_LOCATIONS.readFromMapLocationList(i);
			if (rc.getLocation().isAdjacentTo(pastrLoc)) {
				MessageBoard.TOWER_BUILDER_ROBOT_IDS.claimAssignment(i);
				break;
			}
		}
	}

	static MapLocation here;
	static MapLocation pastr = null;

	private static void turn() throws GameActionException {
		if (!rc.isActive()) return;

		pastr = findNearestAlliedPastr();
		if (pastr == null || here.distanceSquaredTo(pastr) > RobotType.NOISETOWER.attackRadiusMaxSquared) pastr = here;

		herdTowardPastrDumb();
		// herdTowardPastrSmart2();
	}

	private static MapLocation findNearestAlliedPastr() {
		MapLocation[] pastrs = rc.sensePastrLocations(us);
		return Util.closest(pastrs, here);
	}

	static final int maxOrthogonalRadius = (int) Math.sqrt(RobotType.NOISETOWER.attackRadiusMaxSquared);
	static final int maxDiagonalRadius = (int) Math.sqrt(RobotType.NOISETOWER.attackRadiusMaxSquared / 2);
	static Direction attackDir = Direction.NORTH_EAST;
	static int radius = attackDir.isDiagonal() ? maxDiagonalRadius : maxOrthogonalRadius;
	static final int[] nextDumbHerdDir = new int[] { 2, 3, 4, 5, 6, 7, 1, 0 };

	private static void herdTowardPastrDumb() throws GameActionException {
		MapLocation target = null;

		do {
			int dx = radius * attackDir.dx;
			int dy = radius * attackDir.dy;
			target = new MapLocation(pastr.x + dx, pastr.y + dy);

			radius--;
			if (radius <= (attackDir.isDiagonal() ? 3 : 5)) {
				//attackDir = attackDir.rotateRight();
				attackDir = Direction.values()[nextDumbHerdDir[attackDir.ordinal()]];
				radius = attackDir.isDiagonal() ? maxDiagonalRadius : maxOrthogonalRadius;
			}
		} while (tooFarOffMap(target) || !rc.canAttackSquare(target));

		rc.attackSquare(target);
	}

	private static boolean tooFarOffMap(MapLocation loc) {
		int W = 2;
		return loc.x < -W || loc.y < -W || loc.x >= rc.getMapWidth() + W || loc.y >= rc.getMapWidth() + W;
	}

	static MapLocation smart2Loc = null;
	static Direction startDir = Direction.NORTH;

	static int[] edgesX = new int[] { -2, -1, 0, 1, 2, -3, -2, 2, 3, -3, 3, -3, 3, -3, 3, -3, -2, 2, 3, -2, -1, 0, 1, 2 };
	static int[] edgesY = new int[] { 3, 3, 3, 3, 3, 2, 2, 2, 2, 1, 1, 0, 0, -1, -1, -2, -2, -2, -2, -3, -3, -3, -3, -3 };
	static int edgeTrimIndex = 0;

	private static void herdTowardPastrSmart2() throws GameActionException {
		if (smart2Loc == null || smart2Loc.distanceSquaredTo(pastr) <= GameConstants.PASTR_RANGE) {
			smart2Loc = pastr.add(startDir, startDir.isDiagonal() ? maxDiagonalRadius - 3 : maxOrthogonalRadius - 3);
			startDir = startDir.rotateRight();
			edgeTrimIndex = edgesX.length - 1;
//			Debug.indicate("herd", 0, "starting new spoke");
		}

		while (edgeTrimIndex >= 0) {
			MapLocation edge = new MapLocation(pastr.x + edgesX[edgeTrimIndex], pastr.y + edgesY[edgeTrimIndex]);
			edgeTrimIndex--;
			if (isOnMap(edge) && rc.canSenseSquare(edge) && rc.senseCowsAtLocation(edge) > 1000) {
				MapLocation targetSquare = edge.add(pastr.directionTo(edge));
				if (rc.canAttackSquare(targetSquare)) {
					rc.attackSquareLight(targetSquare);
					return;
				}
			}
		}

		while (!rc.canAttackSquare(smart2Loc) || !isOnMap(smart2Loc)) {
			Direction moveDir = smart2Loc.directionTo(pastr);
			Direction computedMoveDir = HerdPattern.readHerdDir(smart2Loc, rc);
			if (computedMoveDir != null) moveDir = computedMoveDir;
			smart2Loc = smart2Loc.add(moveDir);
			if (here.distanceSquaredTo(smart2Loc) > RobotType.NOISETOWER.attackRadiusMaxSquared) {
				smart2Loc = null;
				return;
			}
			if (smart2Loc.equals(pastr)) return; // otherwise in some situations we could get an infinite loop
		}

		Direction herdDir = smart2Loc.directionTo(pastr);
		Direction computedHerdDir = HerdPattern.readHerdDir(smart2Loc, rc);
		if (computedHerdDir != null) herdDir = computedHerdDir;

		MapLocation targetSquare = smart2Loc.add(Util.opposite(herdDir), 3);
//		Debug.indicate("herd", 2, "want to attack " + targetSquare.toString());
		if (rc.canAttackSquare(targetSquare)) rc.attackSquare(targetSquare);
		smart2Loc = smart2Loc.add(herdDir);
	}
}
