package anatid23_twopastr;

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
		// Debug.init(theRC, "herd");

		here = rc.getLocation();

		// claim the assignment to build this tower so others know not to build it
		int numPastrLocations = MessageBoard.NUM_PASTR_LOCATIONS.readInt();
		amSuppressor = true;
		for (int i = 0; i < numPastrLocations; i++) {
			MapLocation pastrLoc = MessageBoard.BEST_PASTR_LOCATIONS.readFromMapLocationList(i);
			if (rc.getLocation().isAdjacentTo(pastrLoc)) {
				amSuppressor = false;
				MessageBoard.TOWER_BUILDER_ROBOT_IDS.claimAssignment(i);
				break;
			}
		}

		if (amSuppressor) {
			int bestDistSq = 999999;
			int numSuppressors = MessageBoard.NUM_SUPPRESSORS.readInt();
			int suppressorIndex = -1;
			for (int i = 0; i < numSuppressors; i++) {
				MapLocation target = MessageBoard.SUPPRESSOR_TARGET_LOCATIONS.readFromMapLocationList(i);
				int distSq = here.distanceSquaredTo(target);
				if (distSq < bestDistSq) {
					bestDistSq = distSq;
					suppressionTarget = target;
					suppressorIndex = i;
				}
			}
			if (suppressorIndex != -1) {
				MessageBoard.SUPPRESSOR_BUILDER_ROBOT_IDS.claimAssignment(suppressorIndex);
			}
		} else {
			// Figure out the best direction to start herding in
			double[] freeCows = new double[8];
			double[][] cowGrowth = rc.senseCowGrowth();
			Direction[] dirs = Direction.values();
			for (int i = 0; i < 8; i++) {
				Direction dir = dirs[i];
				MapLocation probe = here.add(dir);
				while (Util.passable(rc.senseTerrainTile(probe)) && here.distanceSquaredTo(probe) <= RobotType.NOISETOWER.attackRadiusMaxSquared) {
					freeCows[i] += cowGrowth[probe.x][probe.y];
					probe = probe.add(dir);
				}
			}

			double bestScore = -1;
			int bestDir = -1;
			for (int i = 0; i < 8; i++) {
				double score = freeCows[i] + freeCows[(i + 1) % 8] + freeCows[(i + 2) % 8];
				if (score > bestScore) {
					bestScore = score;
					bestDir = i;
				}
			}
			attackDir = dirs[bestDir];
		}
	}

	static MapLocation here;
	static MapLocation pastr = null;
	static boolean amSuppressor;
	static MapLocation suppressionTarget;

	private static void turn() throws GameActionException {
		if (!rc.isActive()) return;

		if (amSuppressor) {
			MapLocation[] theirPastrs = rc.sensePastrLocations(them);
			MapLocation closestEnemyPastr = Util.closest(theirPastrs, here);
			if (closestEnemyPastr != null) {
				if (rc.canAttackSquare(closestEnemyPastr)) {
					rc.attackSquare(closestEnemyPastr);
					return;
				} else {
					MapLocation closer = closestEnemyPastr.add(closestEnemyPastr.directionTo(here));
					if (rc.canAttackSquare(closer)) {
						rc.attackSquare(closer);
						return;
					}
				}
			} else {
				if (suppressionTarget != null && rc.canAttackSquare(suppressionTarget)) {
					rc.attackSquare(suppressionTarget);
					return;
				}
			}
		}

		pastr = findNearestAlliedPastr();
		if (pastr == null || here.distanceSquaredTo(pastr) > 2 * RobotType.NOISETOWER.attackRadiusMaxSquared) {
			pastr = here;
		}

		herdTowardPastrDumb();
		// herdTowardPastrSmart();
	}

	private static MapLocation findNearestAlliedPastr() {
		MapLocation[] pastrs = rc.sensePastrLocations(us);
		return Util.closest(pastrs, here);
	}

	static final int maxOrthogonalRadius = (int) Math.sqrt(RobotType.NOISETOWER.attackRadiusMaxSquared);
	static final int maxDiagonalRadius = (int) Math.sqrt(RobotType.NOISETOWER.attackRadiusMaxSquared / 2);
	static Direction attackDir = Direction.NORTH;
	static int radius = attackDir.isDiagonal() ? maxDiagonalRadius : maxOrthogonalRadius;
	// static final int[] nextDumbHerdDir = new int[] { 2, 3, 4, 5, 6, 7, 1, 0 };
	static final int[] nextDumbHerdDir = new int[] { 1, 2, 3, 4, 5, 6, 7, 0 };

	private static void herdTowardPastrDumb() throws GameActionException {
		MapLocation target = null;

		do {
			int dx = radius * attackDir.dx;
			int dy = radius * attackDir.dy;
			target = new MapLocation(pastr.x + dx, pastr.y + dy);

			radius--;
			if (radius <= (attackDir.isDiagonal() ? 3 : 5)) {
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

	static MapLocation smartLoc = null;
	static Direction nextStartDir = Direction.NORTH;

	static int[] edgesX = new int[] { -2, -1, 0, 1, 2, -3, -2, 2, 3, -3, 3, -3, 3, -3, 3, -3, -2, 2, 3, -2, -1, 0, 1, 2 };
	static int[] edgesY = new int[] { 3, 3, 3, 3, 3, 2, 2, 2, 2, 1, 1, 0, 0, -1, -1, -2, -2, -2, -2, -3, -3, -3, -3, -3 };
	static int edgeTrimIndex = 0;

	private static void herdTowardPastrSmart() throws GameActionException {
		if (smartLoc == null || smartLoc.distanceSquaredTo(pastr) <= GameConstants.PASTR_RANGE) {
			smartLoc = pastr.add(nextStartDir, nextStartDir.isDiagonal() ? maxDiagonalRadius - 3 : maxOrthogonalRadius - 3);
			nextStartDir = nextStartDir.rotateRight();
			edgeTrimIndex = edgesX.length - 1;
			// Debug.indicate("herd", 0, "starting new spoke");
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

		while (!rc.canAttackSquare(smartLoc) || !isOnMap(smartLoc)) {
			Direction moveDir = smartLoc.directionTo(pastr);
			Direction computedMoveDir = HerdPattern.readHerdDir(smartLoc, rc);
			if (computedMoveDir != null) moveDir = computedMoveDir;
			smartLoc = smartLoc.add(moveDir);
			if (here.distanceSquaredTo(smartLoc) > RobotType.NOISETOWER.attackRadiusMaxSquared) {
				smartLoc = null;
				return;
			}
			if (smartLoc.equals(pastr)) return; // otherwise in some situations we could get an infinite loop
		}

		Direction herdDir = smartLoc.directionTo(pastr);
		Direction computedHerdDir = HerdPattern.readHerdDir(smartLoc, rc);
		if (computedHerdDir != null) herdDir = computedHerdDir;

		MapLocation targetSquare = smartLoc.add(Util.opposite(herdDir), 3);
		// Debug.indicate("herd", 2, "want to attack " + targetSquare.toString());
		if (rc.canAttackSquare(targetSquare)) rc.attackSquare(targetSquare);
		smartLoc = smartLoc.add(herdDir);
	}
}
