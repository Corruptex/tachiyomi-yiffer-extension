package eu.kanade.tachiyomi.extension.en.yiffer

import android.net.Uri
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.util.Calendar

class Yiffer : HttpSource() {

    override val name = "Yiffer"

    override val baseUrl = "https://yiffer.xyz"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    private val json: Json by injectLazy()
    private val baseStaticUrl = "https://static.yiffer.xyz"

    private fun buildSearchRequest(
        searchText: String = "",
        page: Int,
        order: String,
        categories: List<String> = listOf(),
        tags: List<String> = listOf(),
        keywords: List<Int> = listOf(),
    ): Request {
        val builder = Uri.Builder()
            .path(SEARCH_URL)
            .appendQueryParameter("search", searchText)
            .appendQueryParameter("page", page.toString())
            .appendQueryParameter("order", order)

        for (category: String in categories) {
            builder.appendQueryParameter("categories[]", category)
        }

        for (tag: String in tags) {
            builder.appendQueryParameter("tags[]", tag)
        }

        for (keywordId: Int in keywords) {
            builder.appendQueryParameter("keywordIds[]", keywordId.toString())
        }

        return GET(baseUrl + builder.build().toString())
    }

    private fun parseSearchResponse(response: Response): MangasPage {
        return response.use {
            json.decodeFromString<SearchResponse>(it.body.string()).let { searchResponse ->
                MangasPage(
                    searchResponse.comics.map {
                        SManga.create().apply {
                            url = "$COMIC_URL/${it.name}"
                            title = it.name
                            artist = it.artist
                            author = it.artist
                            status = SManga.COMPLETED
                            thumbnail_url = "$baseStaticUrl/comics/${it.name}/thumbnail.jpg"
                        }
                    },
                    searchResponse.numberOfPages > searchResponse.page,
                )
            }
        }
    }

    override fun popularMangaRequest(page: Int): Request = buildSearchRequest(page = page, order = "userRating")

    override fun latestUpdatesRequest(page: Int): Request = buildSearchRequest(page = page, order = "updated")

    override fun chapterListParse(response: Response): List<SChapter> {
        response.use { resp ->
            json.decodeFromString<ComicResponse>(resp.body.string()).let { comicResponse ->
                return listOf(
                    SChapter.create().apply {
                        name = "Chapter"
                        date_upload = parseChapterDate(comicResponse.updated)
                        url = "$COMIC_URL/${comicResponse.name}"
                    },
                )
            }
        }
    }

    override fun imageUrlParse(response: Response): String {
        TODO("Not yet implemented")
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return response.use { res ->
            json.decodeFromString<ComicResponse>(res.body.string()).let { searchResponse ->
                SManga.create().apply {
                    title = searchResponse.name
                    thumbnail_url = "/comics/${searchResponse.name}/thumbnail.jpg"
                    status = SManga.COMPLETED
                    artist = searchResponse.artist
                    author = searchResponse.artist
                    description = listOf(
                        Pair("Pages", searchResponse.numberOfPages.toString()),
                        Pair("Category", searchResponse.category),
                        Pair("Tag", searchResponse.tag),
                        Pair("User Rating", searchResponse.userRating.toString()),
                        Pair("Keywords", searchResponse.keywords.joinToString(", ")),
                        Pair("Previous Comic", searchResponse.previousComic),
                        Pair("Next Comic", searchResponse.nextComic),
                    ).filterNot { it.second.isNullOrEmpty() }.joinToString("\n\n") { "${it.first}: ${it.second}" }
                }
            }
        }
    }

    override fun pageListParse(response: Response): List<Page> {
        response.use {
            json.decodeFromString<ComicResponse>(it.body.string()).let { comicResponse ->
                return (1..comicResponse.numberOfPages).map { pageNumber ->
                    Page(pageNumber, "", "$baseStaticUrl/comics/${comicResponse.name}/${String.format("%03d", pageNumber)}.jpg")
                }
            }
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val filterList = if (filters.isEmpty()) getFilterList() else filters

        var order = ""
        val categories = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val keywords = mutableListOf<Int>()

        for (filter in filterList) {
            when (filter) {
                is OrderByFilter -> {
                    order = if (filter.state == 0) {
                        "updated"
                    } else {
                        "userRating"
                    }
                }
                is CategoryList -> {
                    categories += filter.state.filter { it.state }.map { it.name }
                }
                is TagList -> {
                    tags += filter.state.filter { it.state }.map { it.name }
                }
                is KeywordList -> {
                    val keys = getKeywords()
                    keywords += filter.state.filter { checkboxFilter ->
                        checkboxFilter.state
                    }.map { checkboxFilter ->
                        keys.find { it.name == checkboxFilter.name }?.id ?: 0
                    }
                }
                else -> { }
            }
        }

        return buildSearchRequest(
            searchText = query,
            page = page,
            order = order,
            categories = categories,
            tags = tags,
            keywords = keywords,
        )
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$baseUrl$COMIC_URL/${manga.title}")
    }

    override fun searchMangaParse(response: Response): MangasPage = parseSearchResponse(response)

    override fun popularMangaParse(response: Response): MangasPage = parseSearchResponse(response)

    override fun latestUpdatesParse(response: Response): MangasPage = parseSearchResponse(response)

    private fun parseChapterDate(date: String): Long {
        val dateStringSplit = date.split("T")[0].split("-").map { it.toInt() }
        return Calendar.getInstance().apply { add(Calendar.YEAR, dateStringSplit[0]) }.timeInMillis +
            Calendar.getInstance().apply { add(Calendar.MONTH, dateStringSplit[1]) }.timeInMillis +
            Calendar.getInstance().apply { add(Calendar.DATE, dateStringSplit[2]) }.timeInMillis
    }

    private class OrderByFilter : Filter.Select<String>(
        "Order by",
        arrayOf("Recently updated", "User rating"),
    )

    private class CheckboxFilter(name: String) : Filter.CheckBox(name)

    private class CategoryList : Filter.Group<CheckboxFilter>(
        "Categories",
        listOf(
            CheckboxFilter("Furry"),
            CheckboxFilter("MLP"),
            CheckboxFilter("Pokemon"),
            CheckboxFilter("Other"),
        ),
    )
    private class TagList : Filter.Group<CheckboxFilter>(
        "Tags",
        listOf(
            CheckboxFilter("M"),
            CheckboxFilter("F"),
            CheckboxFilter("MF"),
            CheckboxFilter("MM"),
            CheckboxFilter("FF"),
            CheckboxFilter("MF+"),
            CheckboxFilter("I"),
        ),
    )

    private class KeywordList(map: List<String>) : Filter.Group<CheckboxFilter>(
        "Keywords",
        map.map { CheckboxFilter(it) },
    )

    override fun getFilterList(): FilterList = FilterList(
        OrderByFilter(),
        CategoryList(),
        TagList(),
        KeywordList(getKeywords().map { it.name }),
    )

    private fun getKeywords(): List<Keyword> = listOf(
        Keyword("abdl", 862),
        Keyword("absol", 1),
        Keyword("abuse", 882),
        Keyword("age difference", 2),
        Keyword("aggressive retsuko", 326),
        Keyword("alligator", 3),
        Keyword("alpaca", 4),
        Keyword("ampharos", 827),
        Keyword("anal", 5),
        Keyword("andromorph", 875),
        Keyword("animal crossing", 6),
        Keyword("animatronic", 838),
        Keyword("anon-kun", 7),
        Keyword("anthro", 8),
        Keyword("anubis", 9),
        Keyword("apple bloom", 10),
        Keyword("applejack", 11),
        Keyword("arcanine", 12),
        Keyword("asgore", 13),
        Keyword("asriel", 14),
        Keyword("autofellatio", 15),
        Keyword("avian", 16),
        Keyword("badger", 17),
        Keyword("bambi", 18),
        Keyword("bandit heeler", 929),
        Keyword("banjo kazooie", 830),
        Keyword("bara", 853),
        Keyword("bat", 19),
        Keyword("bdsm", 20),
        Keyword("bear", 21),
        Keyword("beastars", 320),
        Keyword("belly bulge", 22),
        Keyword("big ass", 23),
        Keyword("big balls", 24),
        Keyword("big boobs", 25),
        Keyword("big mac", 26),
        Keyword("big penis", 27),
        Keyword("bird", 28),
        Keyword("biting", 29),
        Keyword("blaziken", 30),
        Keyword("blindfolded", 836),
        Keyword("blood", 31),
        Keyword("blowjob", 32),
        Keyword("bondage", 308),
        Keyword("boobjob", 933),
        Keyword("bovine", 33),
        Keyword("bowser", 34),
        Keyword("braeburn", 35),
        Keyword("braixen", 36),
        Keyword("breloom", 37),
        Keyword("brother and sister", 876),
        Keyword("buizel", 38),
        Keyword("bukkake", 39),
        Keyword("bulbasaur", 40),
        Keyword("bull", 867),
        Keyword("bunny", 41),
        Keyword("buttplug", 873),
        Keyword("canine", 42),
        Keyword("canine penis", 43),
        Keyword("casual nudity", 808),
        Keyword("cat", 44),
        Keyword("caught", 45),
        Keyword("charizard", 46),
        Keyword("charmander", 311),
        Keyword("charmeleon", 47),
        Keyword("chastity", 48),
        Keyword("cheetah", 49),
        Keyword("chespin", 50),
        Keyword("chief bogo", 51),
        Keyword("chikorita", 863),
        Keyword("chipmunk", 52),
        Keyword("choking", 822),
        Keyword("christmas", 53),
        Keyword("chubby", 865),
        Keyword("cinderace", 329),
        Keyword("cock vore", 847),
        Keyword("combusken", 54),
        Keyword("condom", 831),
        Keyword("corruption", 885),
        Keyword("cow", 55),
        Keyword("creampie", 56),
        Keyword("creampie eating", 872),
        Keyword("crocodile", 874),
        Keyword("crossdressing", 57),
        Keyword("cubone", 817),
        Keyword("cuckold", 922),
        Keyword("cucksibold", 59),
        Keyword("cum in ass", 800),
        Keyword("cum in mouth", 60),
        Keyword("cum inside", 799),
        Keyword("cum on boobs", 934),
        Keyword("cum on face", 803),
        Keyword("cumflation", 61),
        Keyword("cumshot", 62),
        Keyword("cunnilingus", 63),
        Keyword("curvy", 64),
        Keyword("cute", 65),
        Keyword("dad x son", 899),
        Keyword("deepthroat", 856),
        Keyword("deer", 66),
        Keyword("deino", 67),
        Keyword("demon", 68),
        Keyword("derpy", 69),
        Keyword("dewott", 826),
        Keyword("diaper", 70),
        Keyword("dick on dick", 71),
        Keyword("digestion", 805),
        Keyword("digimon", 72),
        Keyword("dinosaur", 73),
        Keyword("discord", 74),
        Keyword("dog", 75),
        Keyword("donkey", 76),
        Keyword("double penetration", 77),
        Keyword("double penis", 818),
        Keyword("draenei", 78),
        Keyword("dragon", 79),
        Keyword("duck", 80),
        Keyword("dust", 81),
        Keyword("e-stim", 866),
        Keyword("eevee", 82),
        Keyword("eggs", 83),
        Keyword("elephant", 84),
        Keyword("elf", 85),
        Keyword("emolga", 86),
        Keyword("emotional", 87),
        Keyword("enfield", 935),
        Keyword("equine", 88),
        Keyword("espeon", 89),
        Keyword("excessive cum", 802),
        Keyword("exhibitionism", 901),
        Keyword("facesitting", 90),
        Keyword("falco lombardi", 91),
        Keyword("fanmadeby:tricksta", 918),
        Keyword("fat", 92),
        Keyword("father and daugher", 878),
        Keyword("father and son", 879),
        Keyword("feet", 93),
        Keyword("feline", 94),
        Keyword("femboy", 95),
        Keyword("femdom", 96),
        Keyword("fennec", 97),
        Keyword("fennekin", 98),
        Keyword("feral", 99),
        Keyword("feral penis", 100),
        Keyword("feral vagina", 101),
        Keyword("feraligatr", 102),
        Keyword("fetish", 894),
        Keyword("fidget", 103),
        Keyword("fingering", 104),
        Keyword("finnick", 105),
        Keyword("fisting", 106),
        Keyword("fizzle", 107),
        Keyword("flamedramon", 108),
        Keyword("flareon", 109),
        Keyword("fluttershy", 110),
        Keyword("flygon", 111),
        Keyword("fnaf", 112),
        Keyword("footjob", 113),
        Keyword("foreskin", 798),
        Keyword("fox", 114),
        Keyword("fox mccloud", 115),
        Keyword("frog", 317),
        Keyword("frogadier", 116),
        Keyword("frotting", 117),
        Keyword("gag", 861),
        Keyword("gangbang", 868),
        Keyword("gaping", 837),
        Keyword("garble", 118),
        Keyword("garchomp", 119),
        Keyword("gardevoir", 120),
        Keyword("gatomon", 121),
        Keyword("gay", 892),
        Keyword("gay sex", 891),
        Keyword("gazelle", 123),
        Keyword("gilda", 124),
        Keyword("giraffe", 125),
        Keyword("glaceon", 126),
        Keyword("gloryhole", 923),
        Keyword("goat", 127),
        Keyword("goblin", 128),
        Keyword("goo creature", 129),
        Keyword("goodra", 330),
        Keyword("gore", 887),
        Keyword("gouhin", 324),
        Keyword("greninja", 130),
        Keyword("griffin", 131),
        Keyword("group", 132),
        Keyword("group sex", 893),
        Keyword("grovyle", 133),
        Keyword("growlithe", 134),
        Keyword("growth", 902),
        Keyword("guardians of the galaxy", 813),
        Keyword("gym", 840),
        Keyword("haida", 327),
        Keyword("hamster", 921),
        Keyword("handjob", 135),
        Keyword("hands free", 857),
        Keyword("hard vore", 136),
        Keyword("harness", 835),
        Keyword("herm", 305),
        Keyword("hero to villain conversion", 888),
        Keyword("ho-oh", 924),
        Keyword("horse", 137),
        Keyword("horsecock", 138),
        Keyword("houndoom", 139),
        Keyword("houndor", 824),
        Keyword("human", 140),
        Keyword("humiliation", 141),
        Keyword("husky", 881),
        Keyword("hydreigon", 142),
        Keyword("hyena", 143),
        Keyword("hyper", 144),
        Keyword("hypnosis", 145),
        Keyword("impmon", 146),
        Keyword("impregnation", 806),
        Keyword("in heat", 147),
        Keyword("in public", 148),
        Keyword("incest", 149),
        Keyword("incineroar", 313),
        Keyword("inflation", 150),
        Keyword("intersex", 927),
        Keyword("isabelle", 151),
        Keyword("jack", 323),
        Keyword("jackal", 152),
        Keyword("jolteon", 153),
        Keyword("judy hopps", 154),
        Keyword("kalista", 155),
        Keyword("kangaroo", 156),
        Keyword("kindred", 157),
        Keyword("king sombra", 158),
        Keyword("kissing", 159),
        Keyword("knot", 932),
        Keyword("knotting", 160),
        Keyword("knuckles", 161),
        Keyword("kommo-o", 162),
        Keyword("krystal", 163),
        Keyword("kung fu panda", 890),
        Keyword("lapras", 332),
        Keyword("latex", 829),
        Keyword("leafeon", 164),
        Keyword("league of legends", 165),
        Keyword("leavanny", 166),
        Keyword("legoshi", 321),
        Keyword("lemur", 167),
        Keyword("leopard", 168),
        Keyword("licking", 169),
        Keyword("lion", 170),
        Keyword("litten", 318),
        Keyword("lizard", 171),
        Keyword("locker room", 839),
        Keyword("long story", 172),
        Keyword("loona", 833),
        Keyword("lopunny", 173),
        Keyword("louis", 325),
        Keyword("lucario", 174),
        Keyword("lugia", 175),
        Keyword("luxray", 176),
        Keyword("lycanroc", 177),
        Keyword("lynx", 178),
        Keyword("macro", 871),
        Keyword("magic", 179),
        Keyword("maid", 312),
        Keyword("masturbation", 180),
        Keyword("meowth", 815),
        Keyword("mewtwo", 319),
        Keyword("milk", 309),
        Keyword("minccino", 819),
        Keyword("mind control", 883),
        Keyword("minun", 820),
        Keyword("misdreavus", 181),
        Keyword("monkey", 182),
        Keyword("monster", 183),
        Keyword("mordecai (regular show)", 810),
        Keyword("mother and daughter", 880),
        Keyword("mother and son", 807),
        Keyword("mouse", 184),
        Keyword("muscular", 185),
        Keyword("musk", 920),
        Keyword("nasus", 186),
        Keyword("nick wilde", 187),
        Keyword("night elf", 188),
        Keyword("ninetales", 189),
        Keyword("non-anthro", 834),
        Keyword("okapi", 190),
        Keyword("on top", 916),
        Keyword("oral", 315),
        Keyword("orc", 191),
        Keyword("orca", 192),
        Keyword("orgy", 844),
        Keyword("oryx", 193),
        Keyword("otter", 194),
        Keyword("outside", 195),
        Keyword("ox", 196),
        Keyword("pachirisu", 197),
        Keyword("pancham", 825),
        Keyword("panda", 198),
        Keyword("pandaren", 199),
        Keyword("panther", 200),
        Keyword("pat (bluey)", 930),
        Keyword("pegging", 201),
        Keyword("photography", 202),
        Keyword("pichu", 821),
        Keyword("pig", 203),
        Keyword("pikachu", 204),
        Keyword("pinkie pie", 205),
        Keyword("polar bear", 206),
        Keyword("police", 207),
        Keyword("pony", 208),
        Keyword("portal panties", 809),
        Keyword("power bottom", 917),
        Keyword("presenting", 333),
        Keyword("princess cadance", 209),
        Keyword("princess celestia", 210),
        Keyword("princess luna", 211),
        Keyword("public indecency", 900),
        Keyword("public sex", 905),
        Keyword("purugly", 814),
        Keyword("queen chrysalis", 212),
        Keyword("quilava", 310),
        Keyword("rabbit", 213),
        Keyword("raccoon", 214),
        Keyword("raichu", 215),
        Keyword("rainbow dash", 216),
        Keyword("rapidash", 852),
        Keyword("raptor", 218),
        Keyword("rarity", 219),
        Keyword("rat", 220),
        Keyword("ratchet", 221),
        Keyword("rattata", 222),
        Keyword("reality warping", 884),
        Keyword("red panda", 224),
        Keyword("regular show", 812),
        Keyword("removing_the_comic_because_of_tos", 919),
        Keyword("renamon", 225),
        Keyword("renekton", 226),
        Keyword("revenge", 227),
        Keyword("rhino", 228),
        Keyword("riding", 915),
        Keyword("rigby (regular show)", 811),
        Keyword("rimjob", 906),
        Keyword("rimming", 229),
        Keyword("riolu", 230),
        Keyword("robot", 231),
        Keyword("rocket raccoon", 232),
        Keyword("rogue the bat", 233),
        Keyword("role reversal", 845),
        Keyword("romantic", 234),
        Keyword("ronno", 235),
        Keyword("sabertooth", 236),
        Keyword("saliva", 237),
        Keyword("sally acorn", 238),
        Keyword("sandshrew", 239),
        Keyword("scalie", 240),
        Keyword("scat", 869),
        Keyword("sceptile", 241),
        Keyword("scissoring", 242),
        Keyword("scizor", 243),
        Keyword("scootaloo", 244),
        Keyword("scratching", 850),
        Keyword("sea lion", 245),
        Keyword("sex change", 246),
        Keyword("sex slave", 889),
        Keyword("sex toy", 247),
        Keyword("sexting", 848),
        Keyword("shark", 248),
        Keyword("sheath play", 801),
        Keyword("sheep", 249),
        Keyword("shibari", 859),
        Keyword("shining armor", 250),
        Keyword("shinx", 251),
        Keyword("siblings", 877),
        Keyword("size difference", 252),
        Keyword("skunk", 253),
        Keyword("sleeping", 331),
        Keyword("slime creature", 254),
        Keyword("slit", 307),
        Keyword("sly cooper", 255),
        Keyword("small boobs", 860),
        Keyword("snake", 256),
        Keyword("sneasel", 257),
        Keyword("sniffing", 842),
        Keyword("solo", 903),
        Keyword("sonic the hedgehog", 258),
        Keyword("soraka", 259),
        Keyword("spanking", 260),
        Keyword("spike", 261),
        Keyword("spitroast", 904),
        Keyword("spyro", 306),
        Keyword("squirrel", 262),
        Keyword("starfox", 908),
        Keyword("story", 263),
        Keyword("straight to gay conversion", 886),
        Keyword("strangulation", 823),
        Keyword("strapon", 931),
        Keyword("streaking", 870),
        Keyword("surprise gay", 264),
        Keyword("surprise sex", 928),
        Keyword("sweaty", 265),
        Keyword("sweetie belle", 266),
        Keyword("sylveon", 267),
        Keyword("tadano", 328),
        Keyword("tail play", 804),
        Keyword("tailfucking", 268),
        Keyword("tauren", 269),
        Keyword("tentacles", 270),
        Keyword("threesome", 832),
        Keyword("tickling", 828),
        Keyword("tiger", 275),
        Keyword("titjob", 276),
        Keyword("toriel", 277),
        Keyword("torracat", 314),
        Keyword("torture", 849),
        Keyword("trans", 858),
        Keyword("transformation", 278),
        Keyword("treecko", 279),
        Keyword("trixie", 280),
        Keyword("troll", 281),
        Keyword("turtle", 282),
        Keyword("twilight sparkle", 283),
        Keyword("twink", 843),
        Keyword("twokinds", 284),
        Keyword("typhlosion", 316),
        Keyword("umbreon", 285),
        Keyword("undertale", 286),
        Keyword("underwear", 841),
        Keyword("unicorn", 287),
        Keyword("unwilling", 288),
        Keyword("vaginal", 289),
        Keyword("vaporeon", 290),
        Keyword("violence", 291),
        Keyword("vore", 292),
        Keyword("vulpix", 293),
        Keyword("watersports", 294),
        Keyword("weavile", 295),
        Keyword("werewolf", 296),
        Keyword("wolf", 297),
        Keyword("worgen", 298),
        Keyword("workplace sex", 907),
        Keyword("yaoi", 864),
        Keyword("yveltal", 299),
        Keyword("zapdos", 926),
        Keyword("zebra", 300),
        Keyword("zed", 301),
        Keyword("zeraora", 851),
        Keyword("zootopia", 302),
        Keyword("zoroark", 303),
        Keyword("zorua", 304),
        Keyword("zygarde", 925),
    )

    companion object {
        private const val SEARCH_URL = "/api/comicsPaginated"
        private const val COMIC_URL = "/api/comics"
    }
}
