// SPDX-License-Identifier: MIT
package com.xhynv.online;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Simplified-Chinese names for the weapons and vehicles in the trainer's and the room tools' lists.
 * The game supplies those names in its own language; with English game data they arrive in English.
 * A translated entry is shown as "中文名 English", so it can still be recognised and searched either way.
 * Entries not in the tables are left as they are.
 */
public final class Names {
    private static final Map<String, String> BY_MODEL = new HashMap<String, String>(), BY_NAME = new HashMap<String, String>(),
            CATEGORIES = new HashMap<String, String>();

    private Names() {}

    /** model, English name ("" if unknown), Chinese name — repeated. */
    private static void add(String... rows) {
        for (int i = 0; i + 2 < rows.length; i += 3) {
            BY_MODEL.put(rows[i], rows[i + 2]);
            if (!rows[i + 1].isEmpty()) BY_NAME.put(rows[i + 1].toLowerCase(Locale.ROOT), rows[i + 2]);
        }
    }

    static {
        // ---- weapons (keyed without the "weapon_" prefix) ----
        add("unarmed", "Unarmed", "徒手", "dagger", "Antique Cavalry Dagger", "古董骑兵匕首", "bat", "Baseball Bat", "棒球棍",
            "bottle", "Broken Bottle", "碎酒瓶", "crowbar", "Crowbar", "撬棍", "flashlight", "Flashlight", "手电筒",
            "golfclub", "Golf Club", "高尔夫球杆", "hammer", "Hammer", "铁锤", "hatchet", "Hatchet", "手斧",
            "knuckle", "Knuckle Dusters", "指虎", "knife", "Knife", "小刀", "machete", "Machete", "开山刀",
            "switchblade", "Switchblade", "弹簧刀", "nightstick", "Nightstick", "警棍", "wrench", "Pipe Wrench", "管钳",
            "battleaxe", "Battle Axe", "战斧", "poolcue", "Pool Cue", "台球杆", "stone_hatchet", "Stone Hatchet", "石斧",
            "candycane", "Candy Cane", "拐杖糖");
        add("pistol", "Pistol", "手枪", "pistol_mk2", "Pistol Mk II", "手枪 Mk II", "combatpistol", "Combat Pistol", "战斗手枪",
            "appistol", "AP Pistol", "穿甲手枪", "stungun", "Stun Gun", "电击枪", "stungun_mp", "", "电击枪",
            "pistol50", "Pistol .50", ".50 口径手枪", "snspistol", "SNS Pistol", "劣质手枪", "snspistol_mk2", "SNS Pistol Mk II", "劣质手枪 Mk II",
            "heavypistol", "Heavy Pistol", "重型手枪", "vintagepistol", "Vintage Pistol", "老式手枪", "flaregun", "Flare Gun", "信号枪",
            "marksmanpistol", "Marksman Pistol", "射手手枪", "revolver", "Heavy Revolver", "重型左轮手枪",
            "revolver_mk2", "Heavy Revolver Mk II", "重型左轮手枪 Mk II", "doubleaction", "Double Action Revolver", "双动式左轮手枪",
            "raypistol", "Up-n-Atomizer", "原子能手枪", "ceramicpistol", "Ceramic Pistol", "陶瓷手枪",
            "navyrevolver", "Navy Revolver", "海军左轮手枪", "gadgetpistol", "Perico Pistol", "佩里科手枪", "pistolxm3", "WM 29 Pistol", "WM 29 手枪");
        add("microsmg", "Micro SMG", "微型冲锋枪", "smg", "SMG", "冲锋枪", "smg_mk2", "SMG Mk II", "冲锋枪 Mk II",
            "assaultsmg", "Assault SMG", "突击冲锋枪", "combatpdw", "Combat PDW", "作战自卫冲锋枪", "machinepistol", "Machine Pistol", "冲锋手枪",
            "minismg", "Mini SMG", "迷你冲锋枪", "raycarbine", "Unholy Hellbringer", "邪恶冥王", "tecpistol", "Tactical SMG", "战术冲锋枪");
        add("pumpshotgun", "Pump Shotgun", "泵动式霰弹枪", "pumpshotgun_mk2", "Pump Shotgun Mk II", "泵动式霰弹枪 Mk II",
            "sawnoffshotgun", "Sawed-Off Shotgun", "短管霰弹枪", "assaultshotgun", "Assault Shotgun", "突击霰弹枪",
            "bullpupshotgun", "Bullpup Shotgun", "无托式霰弹枪", "musket", "Musket", "老式火枪", "heavyshotgun", "Heavy Shotgun", "重型霰弹枪",
            "dbshotgun", "Double Barrel Shotgun", "双管霰弹枪", "autoshotgun", "Sweeper Shotgun", "冲锋霰弹枪", "combatshotgun", "Combat Shotgun", "战斗霰弹枪");
        add("assaultrifle", "Assault Rifle", "突击步枪", "assaultrifle_mk2", "Assault Rifle Mk II", "突击步枪 Mk II",
            "carbinerifle", "Carbine Rifle", "卡宾步枪", "carbinerifle_mk2", "Carbine Rifle Mk II", "卡宾步枪 Mk II",
            "advancedrifle", "Advanced Rifle", "高级步枪", "specialcarbine", "Special Carbine", "特制卡宾步枪",
            "specialcarbine_mk2", "Special Carbine Mk II", "特制卡宾步枪 Mk II", "bullpuprifle", "Bullpup Rifle", "无托式步枪",
            "bullpuprifle_mk2", "Bullpup Rifle Mk II", "无托式步枪 Mk II", "compactrifle", "Compact Rifle", "紧凑型步枪",
            "militaryrifle", "Military Rifle", "军用步枪", "heavyrifle", "Heavy Rifle", "重型步枪", "tacticalrifle", "Service Carbine", "制式卡宾步枪",
            "battlerifle", "Battle Rifle", "战斗步枪");
        add("mg", "MG", "机枪", "combatmg", "Combat MG", "战斗机枪", "combatmg_mk2", "Combat MG Mk II", "战斗机枪 Mk II",
            "gusenberg", "Gusenberg Sweeper", "古森柏冲锋枪", "sniperrifle", "Sniper Rifle", "狙击步枪", "heavysniper", "Heavy Sniper", "重型狙击步枪",
            "heavysniper_mk2", "Heavy Sniper Mk II", "重型狙击步枪 Mk II", "marksmanrifle", "Marksman Rifle", "射手步枪",
            "marksmanrifle_mk2", "Marksman Rifle Mk II", "射手步枪 Mk II", "precisionrifle", "Precision Rifle", "精确步枪");
        add("rpg", "RPG", "火箭筒", "grenadelauncher", "Grenade Launcher", "榴弹发射器", "grenadelauncher_smoke", "", "烟雾榴弹发射器",
            "minigun", "Minigun", "火神机枪", "firework", "Firework Launcher", "烟花发射器", "railgun", "Railgun", "电磁步枪",
            "railgunxm3", "", "电磁步枪", "hominglauncher", "Homing Launcher", "制导火箭发射器", "compactlauncher", "Compact Grenade Launcher", "紧凑型榴弹发射器",
            "rayminigun", "Widowmaker", "寡妇制造者", "emplauncher", "Compact EMP Launcher", "紧凑型电磁脉冲发射器");
        add("grenade", "Grenade", "手榴弹", "bzgas", "BZ Gas", "BZ 毒气", "smokegrenade", "Tear Gas", "催泪瓦斯", "molotov", "Molotov", "燃烧瓶",
            "stickybomb", "Sticky Bomb", "黏弹", "proxmine", "Proximity Mine", "感应地雷", "snowball", "Snowball", "雪球",
            "pipebomb", "Pipe Bomb", "土制炸弹", "ball", "Ball", "棒球", "flare", "Flare", "信号弹", "petrolcan", "Jerry Can", "汽油桶",
            "hazardcan", "Hazardous Jerry Can", "危险品汽油桶", "fertilizercan", "Fertilizer Can", "化肥罐", "fireextinguisher", "Fire Extinguisher", "灭火器",
            "parachute", "Parachute", "降落伞");

        // ---- vehicles ----
        add("adder", "Adder", "灵蛇", "zentorno", "Zentorno", "桑托劳", "t20", "T20", "T20", "osiris", "Osiris", "奥西里斯",
            "turismor", "Turismo R", "披治 R", "entityxf", "Entity XF", "本质 XF", "cheetah", "Cheetah", "猎豹", "infernus", "Infernus", "炼狱魔",
            "bullet", "Bullet", "子弹", "vacca", "Vacca", "瓦卡", "voltic", "Voltic", "伏特", "sultan", "Sultan", "王者", "elegy2", "Elegy RH8", "挽歌 RH8",
            "jester", "Jester", "小丑", "massacro", "Massacro", "屠夫", "comet2", "Comet", "彗星", "banshee", "Banshee", "女妖",
            "carbonizzare", "Carbonizzare", "卡本尼萨瑞", "coquette", "Coquette", "狂飙", "ninef", "9F", "9F", "feltzer2", "Feltzer", "菲尔泽",
            "dominator", "Dominator", "统治者", "gauntlet", "Gauntlet", "铁腕", "buffalo", "Buffalo", "野牛", "buffalo2", "Buffalo S", "野牛 S",
            "dukes", "Dukes", "公爵", "vigero", "Vigero", "维吉洛", "ruiner", "Ruiner", "毁灭者", "sabregt", "Sabre Turbo", "军刀涡轮",
            "phoenix", "Phoenix", "凤凰", "faction", "Faction", "派系", "blista", "Blista", "布利斯塔", "panto", "Panto", "潘托", "issi2", "Issi", "天威",
            "prairie", "Prairie", "草原", "tailgater", "Tailgater", "尾随者", "oracle", "Oracle XS", "神谕 XS", "fugitive", "Fugitive", "逃亡者",
            "asea", "Asea", "阿西", "schafter2", "Schafter", "沙夫特", "stretch", "Stretch", "加长礼车");
        add("sandking", "Sandking XL", "沙王 XL", "sandking2", "Sandking SWB", "沙王 SWB", "rebel", "Rusty Rebel", "生锈反叛者", "rebel2", "Rebel", "反叛者",
            "bifta", "Bifta", "比夫塔", "bfinjection", "Injection", "沙滩越野车", "dune", "Dune Buggy", "沙丘越野车", "insurgent", "Insurgent", "叛乱分子",
            "insurgent2", "Insurgent Pick-Up", "叛乱分子皮卡", "mesa", "Mesa", "梅萨", "dubsta", "Dubsta", "迪布达", "baller", "Baller", "巴勒",
            "bmx", "BMX", "BMX 小轮车", "cruiser", "Cruiser", "巡洋自行车", "scorcher", "Scorcher", "灼热者山地车", "tribike", "Whippet Race Bike", "灵缇公路赛车",
            "bati", "Bati 801", "巴提 801", "bati2", "Bati 801RR", "巴提 801RR", "akuma", "Akuma", "恶魔", "sanchez", "Sanchez", "桑切斯",
            "pcj", "PCJ-600", "PCJ-600", "daemon", "Daemon", "守护神", "hexer", "Hexer", "六角", "bagger", "Bagger", "巴格", "faggio2", "Faggio", "费吉欧",
            "hakuchou", "Hakuchou", "白鸟", "blazer", "Blazer", "开拓者四轮摩托");
        add("police", "Police Cruiser", "警用巡洋舰", "police2", "Police Cruiser", "警用野牛", "police3", "Police Cruiser", "警用拦截者",
            "police4", "Unmarked Cruiser", "无标志警车", "policeb", "Police Bike", "警用摩托", "polmav", "Police Maverick", "警用小蛮牛",
            "sheriff", "Sheriff Cruiser", "警长巡逻车", "sheriff2", "Sheriff SUV", "警长 SUV", "fbi", "FIB", "FIB 野牛", "fbi2", "FIB", "FIB SUV",
            "riot", "Police Riot", "防暴车", "pbus", "Prison Bus", "监狱巴士", "ambulance", "Ambulance", "救护车", "firetruk", "Fire Truck", "消防车",
            "rhino", "Rhino Tank", "犀牛坦克", "barracks", "Barracks", "军用运兵车", "crusader", "Crusader", "十字军", "bus", "Bus", "公交车",
            "coach", "Dashound", "长途客车", "taxi", "Taxi", "出租车", "mule", "Mule", "骡子", "benson", "Benson", "本森", "pounder", "Pounder", "庞德",
            "phantom", "Phantom", "幻影卡车头", "packer", "Packer", "帕克", "dump", "Dump", "矿用自卸车", "bulldozer", "Dozer", "推土机",
            "tractor", "Tractor", "拖拉机", "forklift", "Forklift", "叉车", "trash", "Trashmaster", "垃圾车", "towtruck", "Towtruck", "拖车",
            "stockade", "Stockade", "运钞车");
        add("dinghy", "Dinghy", "小艇", "jetmax", "Jetmax", "喷射快艇", "marquis", "Marquis", "侯爵帆船", "speeder", "Speeder", "极速快艇",
            "squalo", "Squalo", "斯夸洛", "suntrap", "Suntrap", "阳光游艇", "tropic", "Tropic", "热带快艇", "seashark", "Seashark", "海鲨摩托艇",
            "submersible", "Submersible", "潜水艇", "predator", "Police Predator", "警用掠食者", "buzzard", "Buzzard Attack Chopper", "秃鹰攻击直升机",
            "buzzard2", "Buzzard", "秃鹰", "frogger", "Frogger", "蛙人", "maverick", "Maverick", "小蛮牛", "annihilator", "Annihilator", "歼灭者",
            "cargobob", "Cargobob", "运兵直升机", "swift", "Swift", "迅捷", "savage", "Savage", "野蛮人", "valkyrie", "Valkyrie", "女武神",
            "skylift", "Skylift", "天空吊运机", "lazer", "P-996 LAZER", "P-996 天煞", "hydra", "Hydra", "九头蛇", "besra", "Besra", "贝斯拉",
            "miljet", "Miljet", "军用喷气机", "luxor", "Luxor", "卢克索", "shamal", "Shamal", "夏马尔", "duster", "Duster", "喷药机",
            "mammatus", "Mammatus", "马玛特斯", "stunt", "Mallard", "特技飞机", "velum", "Velum", "维伦", "titan", "Titan", "泰坦",
            "cuban800", "Cuban 800", "古巴 800", "cargoplane", "Cargo Plane", "货机", "jet", "Jet", "喷气客机", "blimp", "Atomic Blimp", "原子飞艇");
        add("kuruma", "Kuruma", "骷髅马", "kuruma2", "Kuruma (Armored)", "装甲骷髅马", "technical", "Technical", "武装皮卡",
            "monster", "Liberator", "自由者大脚车", "marshall", "Marshall", "元帅大脚车", "dukes2", "Duke O'Death", "死亡公爵",
            "nightshade", "Nightshade", "夜影", "reaper", "Reaper", "死神", "fmj", "FMJ", "FMJ", "pfister811", "811", "811",
            "prototipo", "X80 Proto", "X80 原型车", "nero", "Nero", "尼罗", "tempesta", "Tempesta", "暴风", "italigtb", "Itali GTB", "伊塔里 GTB",
            "vagner", "Vagner", "瓦格纳", "xa21", "XA-21", "XA-21", "cyclone", "Cyclone", "旋风", "visione", "Visione", "幻象",
            "autarch", "Autarch", "霸主", "sc1", "SC1", "SC1", "entity2", "Entity XXR", "本质 XXR", "taipan", "Taipan", "太攀蛇",
            "tezeract", "Tezeract", "泰兹拉克特", "emerus", "Emerus", "艾麦鲁斯", "krieger", "Krieger", "克里格", "thrax", "Thrax", "斯拉克斯",
            "oppressor", "Oppressor", "暴君", "oppressor2", "Oppressor Mk II", "暴君 Mk II", "deluxo", "Deluxo", "德罗索",
            "vigilante", "Vigilante", "复仇者", "khanjali", "TM-02 Khanjali", "TM-02 可汗贾利", "akula", "Akula", "阿库拉", "hunter", "FH-1 Hunter", "FH-1 猎杀者",
            "shotaro", "Shotaro", "正太郎", "sanctus", "Sanctus", "圣徒");

        String[] categories = {
            "compacts", "小型车", "sedans", "轿车", "suvs", "SUV", "coupes", "轿跑车", "muscle", "肌肉车", "sports classics", "经典跑车",
            "sports classic", "经典跑车", "sports", "跑车", "super", "超级跑车", "motorcycles", "摩托车", "off-road", "越野车", "offroad", "越野车",
            "industrial", "工业车辆", "utility", "公用车辆", "vans", "厢型车", "cycles", "自行车", "boats", "船只", "helicopters", "直升机",
            "planes", "飞机", "service", "服务车辆", "emergency", "应急车辆", "military", "军用车辆", "commercial", "商用车辆", "trains", "火车",
            "open wheel", "开轮式赛车", "melee", "近战武器", "handguns", "手枪", "pistols", "手枪", "pistol", "手枪", "submachine guns", "冲锋枪",
            "smg", "冲锋枪", "smgs", "冲锋枪", "shotguns", "霰弹枪", "shotgun", "霰弹枪", "assault rifles", "突击步枪", "rifles", "步枪", "rifle", "步枪",
            "light machine guns", "轻机枪", "machine guns", "机枪", "mg", "机枪", "sniper rifles", "狙击步枪", "sniper", "狙击步枪", "snipers", "狙击步枪",
            "heavy weapons", "重型武器", "heavy", "重型武器", "throwables", "投掷武器", "thrown", "投掷武器", "throwable", "投掷武器",
            "miscellaneous", "其他", "misc", "其他", "other", "其他", "weapons", "武器", "weapon", "武器", "vehicles", "车辆", "vehicle", "车辆",
        };
        for (int i = 0; i + 1 < categories.length; i += 2) CATEGORIES.put(categories[i], categories[i + 1]);
    }

    private static boolean chinese(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) return true;
        }
        return false;
    }

    /** The Chinese name for a model id or an English display name; null if not in the tables. */
    static String translate(String model, String... names) {
        String key = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (key.startsWith("weapon_") || key.startsWith("gadget_")) key = key.substring(7);
        String found = BY_MODEL.get(key);
        for (int i = 0; found == null && i < names.length; i++) {
            if (names[i] != null) found = BY_NAME.get(names[i].trim().toLowerCase(Locale.ROOT));
        }
        return found;
    }

    /** The name of a nearby vehicle or object in the trainer: Chinese when the model is known. */
    public static String entity(String label, String model) {
        try {
            String shown = label == null ? "" : label.trim();
            if (chinese(shown)) return shown;
            String zh = translate(model, shown);
            if (zh != null) return zh;
            return shown.isEmpty() ? (model == null ? "" : model) : shown;
        } catch (Throwable e) {
            return label == null || label.isEmpty() ? model : label;
        }
    }

    /** Called by the game for every catalogue entry before it reads the entry's fields. */
    public static void catalog(JSONObject item) {
        try {
            String label = item.optString("label", "").trim(), display = item.optString("display", "").trim();
            String category = item.optString("categoryLabel", "").trim();
            String categoryZh = CATEGORIES.get(category.toLowerCase(Locale.ROOT));
            if (categoryZh != null) item.put("categoryLabel", categoryZh);
            if (chinese(label) || chinese(display)) return;   // the game data is already Chinese
            String zh = translate(item.optString("model", ""), display, label);
            if (zh == null) return;
            String english = !display.isEmpty() ? display : label;
            String shown = english.isEmpty() || english.equalsIgnoreCase(zh) ? zh : zh + " " + english;
            item.put("label", shown);
            item.put("display", shown);
            item.put("search", item.optString("search", "") + " " + zh);
        } catch (Throwable ignored) {
            // A list entry keeps its original name.
        }
    }
}
