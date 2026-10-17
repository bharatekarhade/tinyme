"""Sample data for the README screenshots, as SQL for the tinyme_demo database only. Run by capture.sh."""
import json
import random
import uuid
from datetime import date, datetime, timedelta, timezone

random.seed(7)
JST = timezone(timedelta(hours=9))
TODAY = datetime.now(JST).date()
NOW_HOUR = datetime.now(JST).hour  # meals and workouts later today are left out
out = []


def q(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def j(v):
    return q(json.dumps(v, ensure_ascii=False)) + "::jsonb"


def ts(day, h, m=0):
    return datetime(day.year, day.month, day.day, h, m, tzinfo=JST).isoformat()


out.append("BEGIN;")
out.append("UPDATE account SET display_name = 'Alex';")
out.append("INSERT INTO settings (key, value) VALUES ('user.tz', '\"Asia/Tokyo\"'), ('user.currency', '\"JPY\"') "
           "ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;")

kinds = {
    "meal": ("Meals and snacks", {"kcal": "number", "protein_g": "number", "carbs_g": "number", "fat_g": "number"}),
    "drink": ("Coffee, tea and other drinks", {"type": "text", "kcal": "number"}),
    "expense": ("Money spent", {"amount": "number", "currency": "text", "category": "text", "merchant": "text"}),
    "workout": ("Gym sessions, runs and other training", {"type": "text", "minutes": "number"}),
    "reading": ("Books and pages read", {"title": "text", "pages": "number"}),
    "journal": ("Thoughts and notes about the day", {}),
}
for kind, (desc, hints) in kinds.items():
    out.append(f"INSERT INTO entry_kinds (kind, description, data_hints) VALUES ({q(kind)}, {q(desc)}, {j(hints)}) "
               "ON CONFLICT (kind) DO NOTHING;")


def entry(day, h, m, kind, text, data, qty=1):
    out.append("INSERT INTO entries (ts, local_day, kind, quantity, text, data, source) VALUES "
               f"({q(ts(day, h, m))}, {q(day.isoformat())}, {q(kind)}, {qty}, {q(text)}, {j(data)}, 'chat');")


meals = [
    ("Overnight oats with berries", 380, 14, 58, 9), ("Salmon onigiri and miso soup", 420, 22, 60, 8),
    ("Chicken katsu curry", 780, 32, 96, 28), ("Tuna poke bowl", 560, 34, 62, 16),
    ("Soba with tempura", 640, 20, 88, 22), ("Greek yogurt and granola", 310, 18, 40, 8),
    ("Grilled mackerel set", 590, 38, 52, 22), ("Margherita pizza", 820, 30, 98, 32),
    ("Avocado toast and eggs", 450, 20, 34, 26), ("Ramen at Afuri", 690, 26, 82, 24),
]
merchants = [("Blue Bottle Coffee", "coffee", 650), ("FamilyMart", "groceries", 820), ("Suica top-up", "transport", 3000),
             ("Afuri Ebisu", "food", 1290), ("Muji", "shopping", 2490), ("Life supermarket", "groceries", 3460),
             ("Tsutaya Books", "books", 1980), ("Uniqlo", "shopping", 3990), ("Ichiran", "food", 1180)]
workouts = [("Push day", 70), ("Pull day", 65), ("Legs", 75), ("Easy run", 35), ("Yoga", 45)]
notes = ["Shipped the new onboarding flow. Felt good to finally close it out.",
         "Long walk along the Meguro river after work, the evenings are getting cooler.",
         "Slept badly, too much coffee after 4pm. Cutting it off at lunch tomorrow.",
         "Called Aiko for her new job. She starts in November.",
         "Finished the first half of Project Hail Mary on the train."]

start = TODAY - timedelta(days=34)
for i in range(35):
    day = start + timedelta(days=i)
    today = day == TODAY
    weekday = day.weekday()
    # coffee most mornings
    for c in range(1 if today else random.choice([1, 1, 2])):
        entry(day, 8 + c * 5, 15, "drink", "Flat white", {"type": "flat white", "kcal": 120})
    # meals: breakfast, lunch, dinner (today: breakfast and lunch only)
    picked = random.sample(meals, 3)
    for slot, (name, kcal, p, cb, f) in zip([(8, 30), (12, 40), (19, 30)], picked):
        if today and slot[0] > NOW_HOUR:
            continue
        entry(day, slot[0], slot[1], "meal", name, {"kcal": kcal, "protein_g": p, "carbs_g": cb, "fat_g": f})
    # spending
    recent = day >= TODAY - timedelta(days=2)
    pool = [m for m in merchants if m[2] < 1500] if recent else merchants
    picks = [merchants[0]] if today else random.sample(pool, random.choice([1, 2, 3]))
    for merchant, category, amount in picks:
        if not today and category != "transport":
            amount += random.choice([0, 0, 120, -80])
        entry(day, random.choice([9, 13, 18]), 5, "expense", merchant,
              {"amount": amount, "currency": "JPY", "category": category, "merchant": merchant})
    # workouts: Mon, Tue, Thu, Sat, with a few skips in older weeks
    if weekday in (0, 1, 3, 5) and (day >= TODAY - timedelta(days=5) or random.random() > 0.25):
        if not (today and NOW_HOUR < 18):
            name, minutes = workouts[[0, 3, 1, 2][[0, 1, 3, 5].index(weekday)]]
            entry(day, 7, 0, "workout", name, {"type": name, "minutes": minutes})
    if random.random() > 0.5 or today:
        entry(day, 21, 30, "reading", "Project Hail Mary", {"title": "Project Hail Mary", "pages": random.choice([18, 24, 32, 40])})
    if random.random() > 0.6:
        entry(day, 22, 10, "journal", random.choice(notes), {})

# Home cards (the Journal card is seeded by the server)
cards = [
    ("calories", "Calories", {"v": 1, "title": "Calories", "icon": "flame", "accent": "coral", "tone": "surface",
     "badge": {"kind": "period", "block": 0},
     "blocks": [{"type": "progress", "label": "kcal", "style": "gauge",
                 "metric": {"kinds": ["meal", "drink"], "op": "sum", "field": "kcal"}, "period": "today", "goal": 2200, "unit": "kcal"},
                {"type": "goals", "period": "today", "items": [
                    {"label": "Protein", "metric": {"kinds": ["meal", "drink"], "op": "sum", "field": "protein_g"}, "goal": 120, "unit": "g"},
                    {"label": "Carbs", "metric": {"kinds": ["meal", "drink"], "op": "sum", "field": "carbs_g"}, "goal": 250, "unit": "g"},
                    {"label": "Fat", "metric": {"kinds": ["meal", "drink"], "op": "sum", "field": "fat_g"}, "goal": 70, "unit": "g"}]}]}),
    ("gym", "Gym", {"v": 1, "title": "Gym", "icon": "dumbbell", "accent": "ink", "tone": "ink",
     "blocks": [{"type": "checkins", "label": "visits", "metric": {"kinds": ["workout"], "op": "count"},
                 "period": "range", "goal": 4, "goal_period": "week"},
                {"type": "latest", "label": "Last session", "kinds": ["workout"], "fields": ["type", "minutes"]}]}),
    ("spending", "Spending", {"v": 1, "title": "Spending", "icon": "wallet", "accent": "sunny", "tone": "surface",
     "badge": {"kind": "delta", "block": 0},
     "blocks": [{"type": "stat", "label": "spent", "metric": {"kinds": ["expense"], "op": "sum", "field": "amount"},
                 "period": "range", "goal": 30000, "goal_period": "week", "unit": "JPY", "compare": "previous_period", "better": "lower"},
                {"type": "breakdown", "label": "by category", "metric": {"kinds": ["expense"], "op": "sum", "field": "amount"},
                 "period": "range", "by": "category", "top": 3, "style": "segmented", "unit": "JPY"}]}),
]
for pos, (slug, title, spec) in enumerate(cards, start=1):
    out.append("INSERT INTO cards (slug, title, spec, status, position, activated_at) VALUES "
               f"({q(slug)}, {q(title)}, {j(spec)}, 'active', {pos}, now());")
reading_card = str(uuid.UUID("01a14365-0000-7000-8000-00000000c0de"))
reading_spec = {"v": 1, "title": "Reading", "icon": "book", "accent": "mint", "tone": "surface",
                "blocks": [{"type": "stat", "label": "pages this week", "metric": {"kinds": ["reading"], "op": "sum", "field": "pages"},
                            "period": "range", "goal": 150, "goal_period": "week", "unit": "pages", "compare": "previous_period", "better": "higher"},
                           {"type": "chart", "label": "per day", "style": "bars", "bucket": "auto",
                            "metric": {"kinds": ["reading"], "op": "sum", "field": "pages"}, "period": "range", "highlight": "peak"}]}
out.append(f"INSERT INTO cards (id, slug, title, spec, status, position) VALUES ({q(reading_card)}, 'reading', 'Reading', {j(reading_spec)}, 'draft', 0);")

# People
people = [("kenji", "Kenji Sato", "friend", (10, 24, 1993)), ("aiko", "Aiko", "sister", (3, 9, None)),
          ("sam", "Sam Okafor", "coworker", None)]
for slug, name, rel, bday in people:
    m, d, y = bday if bday else (None, None, None)
    out.append("INSERT INTO people (slug, display_name, relationship, memory_path, birthday_month, birthday_day, birthday_year) VALUES "
               f"({q(slug)}, {q(name)}, {q(rel)}, {q('/people/' + slug + '.md')}, {q(m)}, {q(d)}, {q(y)});")

# Places around Shibuya and Ebisu
places = [("Blue Bottle Coffee Shibuya", "1-17-5 Jinnan, Shibuya", "Jinnan, Shibuya", 35.6640, 139.6982, "cafe", "Best pour-over nearby", True),
          ("Afuri Ebisu", "1-1-7 Ebisu, Shibuya", "Ebisu", 35.6467, 139.7101, "restaurant", "Yuzu shio ramen", True),
          ("Tsutaya Books Daikanyama", "17-5 Sarugakucho, Shibuya", "Daikanyama", 35.6489, 139.6997, "bookstore", None, False),
          ("Yoyogi Park", "2-1 Yoyogikamizonocho, Shibuya", "Yoyogi", 35.6717, 139.6949, "park", "Sunday morning runs", True),
          ("Onibus Coffee Nakameguro", "2-14-1 Kamimeguro, Meguro", "Nakameguro", 35.6440, 139.6990, "cafe", "Try the Kenya roast", False),
          ("Sushi Saito", "1-4-5 Roppongi, Minato", "Roppongi", 35.6655, 139.7400, "restaurant", "Anniversary dinner?", False)]
for name, addr, area, lat, lng, cat, note, visited in places:
    out.append("INSERT INTO places (name, address, area, lat, lng, category, note, visited_at, source) VALUES "
               f"({q(name)}, {q(addr)}, {q(area)}, {lat}, {lng}, {q(cat)}, {q(note)}, {'now()' if visited else 'NULL'}, 'chat');")

# Plans
def plan(kind, title, day, start=None, end=None, place=None, notes=None, freq=None, habit=None, remind=None,
         source="chat", external=None):
    # external: gmail:<message id> (found in email), linkedin:<job id> (a job alert), or a Google Calendar event id.
    out.append("INSERT INTO plans (kind, title, notes, place, zone, start_day, start_time, end_time, repeat_freq, habit_kind, remind_minutes, source, external_id) VALUES "
               f"({q(kind)}, {q(title)}, {q(notes)}, {q(place)}, 'Asia/Tokyo', {q(day.isoformat())}, {q(start)}, {q(end)}, {q(freq)}, {q(habit)}, {q(remind)}, {q(source)}, {q(external)});")

plan("event", "Team standup", TODAY - timedelta(days=14), "10:00", "10:15", freq="daily")
plan("event", "Lunch with Kenji", TODAY, "12:30", "13:30", place="Afuri Ebisu", remind=30)
plan("event", "Design review", TODAY, "15:30", "16:30", place="Room 4B", source="google", external="demo-gcal-1")
plan("event", "Climbing with Sam", TODAY, "19:00", "20:30", place="B-Pump Ogikubo")
plan("task", "Renew passport", TODAY)
plan("task", "Apply: Mobile Engineer at SmartNews", TODAY, notes="Tokyo, Japan (Remote)", external="linkedin:4011112222")
plan("task", "Pay water bill", TODAY, external="gmail:demo-msg-6")
plan("task", "Book flights for New Year", TODAY + timedelta(days=1))
plan("event", "Dentist", TODAY + timedelta(days=3), "09:30", "10:15", place="Ebisu Dental")
plan("event", "Gym", TODAY - timedelta(days=21), "07:00", "08:00", freq="weekly", habit="workout")

# G Scan: Google looks connected; nothing here ever talks to Google.
out.append("INSERT INTO google_account (email, scopes, refresh_token_enc, status, connected_at) VALUES "
           "('alex.demo@gmail.com', ARRAY['openid','email',"
           "'https://www.googleapis.com/auth/gmail.readonly','https://www.googleapis.com/auth/gmail.compose',"
           "'https://www.googleapis.com/auth/calendar.events','https://www.googleapis.com/auth/calendar.calendarlist.readonly',"
           "'https://www.googleapis.com/auth/calendar.events.freebusy','https://www.googleapis.com/auth/calendar.app.created'], "
           "'\\x00'::bytea, 'connected', now());")


def suggestion(kind, payload, evidence, msg):
    out.append("INSERT INTO suggestions (gmail_message_id, type, payload, confidence, evidence) VALUES "
               f"({q(msg)}, {q(kind)}, {j(payload)}, 0.93, {q(evidence)});")


suggestion("payment", {"title": "Blue Bottle Coffee", "merchant": "Blue Bottle Coffee", "day": TODAY.isoformat(), "time": "08:12",
                       "amount": "650", "currency": "JPY", "direction": "purchase", "provisional": False, "card": "Visa ··4021",
                       "reference": "A10293"}, "ご利用金額 650円", "demo-msg-1")
suggestion("payment", {"title": "Netflix", "merchant": "Netflix", "day": (TODAY - timedelta(days=1)).isoformat(),
                       "amount": "15.49", "currency": "USD", "direction": "purchase", "provisional": False,
                       "card": "Mastercard ··8812", "reference": "NF-55120"}, "Amount: USD 15.49", "demo-msg-2")
suggestion("payment", {"title": "Amazon.co.jp", "merchant": "Amazon.co.jp", "day": (TODAY - timedelta(days=1)).isoformat(),
                       "amount": "2380", "currency": "JPY", "direction": "refund", "provisional": False,
                       "card": "Visa ··4021", "reference": "R-7781"}, "返金額 2,380円", "demo-msg-3")
suggestion("event", {"title": "Dinner at Sushi Saito", "day": (TODAY + timedelta(days=2)).isoformat(), "time": "19:00",
                     "end_time": "21:00", "place": "Sushi Saito, Roppongi"}, "Your reservation for 2 on Sunday at 7:00 PM", "demo-msg-4")
suggestion("task", {"title": "Pay electricity bill", "day": (TODAY + timedelta(days=4)).isoformat()},
           "Please pay ¥6,840 by October 20", "demo-msg-5")

for n, (title, company, loc, status) in enumerate([("Senior iOS Engineer", "Mercari", "Tokyo, Japan (Hybrid)", "new"),
                                                  ("iOS Team Lead", "LY Corporation", "Tokyo, Japan", "new"),
                                                  ("Staff Backend Engineer, Payments", "PayPay", "Tokyo, Japan", "applied"),
                                                  ("Mobile Engineer", "SmartNews", "Tokyo, Japan (Remote)", "interested")]):
    job_id = 4011110000 + n
    out.append("INSERT INTO job_leads (dedupe_key, title, company, location, url, gmail_message_id, status) VALUES "
               f"({q(f'linkedin:{job_id}')}, {q(title)}, {q(company)}, {q(loc)}, {q(f'https://www.linkedin.com/jobs/view/{job_id}/')}, 'demo-jobs', {q(status)});")

# Today's chat, and an email the agent drafted that waits for a tap
session = str(uuid.UUID("01a14365-0000-7000-8000-00000000c001"))
out.append(f"INSERT INTO agent_sessions (id, anthropic_session_id, agent, agent_version, kind, local_day) VALUES "
           f"({q(session)}, 'sesn_demo_readme', 'chat', 1, 'chat', {q(TODAY.isoformat())});")


def message(role, content, h, m, actions=()):
    out.append("INSERT INTO messages (session_id, role, content, actions, created_at) VALUES "
               f"({q(session)}, {q(role.upper())}, {q(content)}, {j(list(actions))}, {q(ts(TODAY, h, m))});")


def action(tool, summary, ref=None):
    return {"tool": tool, "summary": summary, "is_error": False, "ref": ref}


message("user", "Had a flat white at Blue Bottle and the salmon onigiri for breakfast", 8, 40)
message("assistant", "Logged both. That's **540 kcal** so far today, and your 9th coffee this week.", 8, 40,
        [action("entries_add", "Logged drink · Flat white"), action("entries_add", "Logged meal · Salmon onigiri")])
message("user", "Lunch with Kenji at Afuri today at 12:30, remind me 30 min before", 9, 12)
message("assistant", "Added **Lunch with Kenji** at 12:30 at Afuri Ebisu. I'll remind you at 12:00.", 9, 12,
        [action("people_get", "Found Kenji Sato"), action("plans_add", "Added event · Lunch with Kenji")])
message("user", "Can you make a card for my reading? Goal is 150 pages a week", 14, 50)
message("assistant", "Here's a **Reading** card tracking pages against 150 a week. You're at 112 so far.", 14, 50,
        [action("cards_list", "Listed 4 cards"), action("cards_save", "Saved draft card · Reading", {"card_id": reading_card})])

tool_call = str(uuid.UUID("01a14365-0000-7000-8000-00000000c002"))
out.append(f"INSERT INTO tool_calls (id, session_id, anthropic_event_id, tool, input, status) VALUES "
           f"({q(tool_call)}, {q(session)}, 'demo-event-1', 'gmail_send', '{{}}'::jsonb, 'waiting_confirmation');")
body = "Hi Kenji,\n\nGreat catching up today. Sunday at Sushi Saito works for me, I'll book for 7pm.\n\nAlex"
out.append("INSERT INTO confirmations (id, session_id, tool_call_id, tool, payload, status, expires_at) VALUES "
           f"(gen_random_uuid(), {q(session)}, {q(tool_call)}, 'gmail_send', "
           + j({"kind": "email", "draft_id": "demo-draft", "to": "kenji.sato@example.com", "cc": "", "bcc": "",
                "subject": "Sunday dinner", "body": body, "preview": body[:300], "attachments": [], "draft_hash": "demo"})
           + ", 'pending', now() + interval '7 days');")

out.append("COMMIT;")
print("\n".join(out))
