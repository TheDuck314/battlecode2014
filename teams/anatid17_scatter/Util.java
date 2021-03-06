package anatid17_scatter;

import battlecode.common.*;

public class Util {
	public static boolean passable(TerrainTile t) {
		return t == TerrainTile.NORMAL || t == TerrainTile.ROAD;
	}

	public static MapLocation closest(MapLocation[] locs, MapLocation to) {
		MapLocation ret = null;
		int bestDistSq = 999999;
		for (int i = locs.length; i-- > 0;) {
			int distSq = locs[i].distanceSquaredTo(to);
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				ret = locs[i];
			}
		}
		return ret;
	}

	public static MapLocation closest(RobotInfo[] infos, MapLocation here) {
		MapLocation ret = null;
		int bestDistSq = 999999;
		for (int i = infos.length; i-- > 0;) {
			RobotInfo info = infos[i];
			MapLocation loc = info.location;
			int distSq = loc.distanceSquaredTo(here);
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				ret = loc;
			}
		}
		return ret;
	}

	public static boolean contains(MapLocation[] locs, MapLocation x) {
		for (int i = locs.length; i-- > 0;) {
			if (x.equals(locs[i])) return true;
		}
		return false;
	}

	public static int countSoldiers(RobotInfo[] infos) throws GameActionException {
		int ret = 0;
		for (int i = infos.length; i-- > 0;) {
			if (infos[i].type == RobotType.SOLDIER) ret++;
		}
		return ret;
	}

	public static RobotInfo[] senseAllInfos(Robot[] bots, RobotController rc) throws GameActionException {
		RobotInfo[] ret = new RobotInfo[bots.length];
		for (int i = bots.length; i-- > 0;) {
			ret[i] = rc.senseRobotInfo(bots[i]);
		}
		return ret;
	}

	public static MapLocation closestNonHQ(RobotInfo[] infos, RobotController rc) {
		MapLocation ret = null;
		int bestDistSq = 999999;
		for (int i = infos.length; i-- > 0;) {
			RobotInfo info = infos[i];
			if (info.type == RobotType.HQ) continue;
			MapLocation loc = info.location;
			int distSq = loc.distanceSquaredTo(rc.getLocation());
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				ret = loc;
			}
		}
		return ret;
	}

	public static MapLocation closestSoldier(RobotInfo[] infos, MapLocation here) {
		MapLocation ret = null;
		int bestDistSq = 999999;
		for (int i = infos.length; i-- > 0;) {
			RobotInfo info = infos[i];
			if (info.type != RobotType.SOLDIER) continue;
			MapLocation loc = info.location;
			int distSq = loc.distanceSquaredTo(here);
			if (distSq < bestDistSq) {
				bestDistSq = distSq;
				ret = loc;
			}
		}
		return ret;
	}

	public static boolean containsNoiseTower(Robot[] robots, RobotController rc) throws GameActionException {
		for (int i = robots.length; i-- > 0;) {
			if (rc.senseRobotInfo(robots[i]).type == RobotType.NOISETOWER) return true;
		}
		return false;
	}

	public static boolean containsConstructingRobotOrNoiseTower(Robot[] robots, RobotController rc) throws GameActionException {
		for (int i = robots.length; i-- > 0;) {
			RobotInfo info = rc.senseRobotInfo(robots[i]);
			if (info.type == RobotType.NOISETOWER || info.isConstructing) return true;
		}
		return false;
	}

	public static Direction opposite(Direction dir) {
		return Direction.values()[(dir.ordinal() + 4) % 8];
	}

	public static boolean inHQAttackRange(MapLocation loc, MapLocation hq) {
		int distSq = hq.distanceSquaredTo(loc);
		if (distSq < 25) return true;
		else if (distSq > 25) return false;
		else return (loc.x != hq.x) && (loc.y != hq.y);
	}

	public static boolean isOnMap(MapLocation loc, RobotController rc) {
		return loc.x >= 0 && loc.y >= 0 && loc.x < rc.getMapWidth() && loc.y < rc.getMapHeight();
	}
}
