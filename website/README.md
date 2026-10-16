# TinyMe website

The TinyMe landing page: a scroll story where a phone shows the app while an illustrated person logs his day by talking. It's a plain static site (HTML, CSS and one JavaScript file) with no build step and no dependencies.

## 1. Change the values

| What | Where |
|---|---|
| GitHub link, contact email, footer year | `config.js`. Leave a value as `''` to hide whatever uses it. |
| Page title, description, and your site's address for link previews | The `EDIT ME` block at the top of `index.html`. Replace `https://tinyme.example.com` with your domain in the three places it appears. |
| Any wording on the page | Search for the text in `index.html` and edit it there. |
| Colours, spacing and fonts | `styles.css`, or the inline styles in `index.html`. |
| Link preview image | `assets/og.png` (1200 × 630). |

## 2. Preview locally

From this folder, run either of these and open http://localhost:4173:

```sh
python3 -m http.server 4173
# or
npx serve -l 4173
```

Open it over `http://`, not by double-clicking `index.html`. Browsers block some features on `file://` pages.

## 3. Deploy to Vercel

**From GitHub (recommended).** Push this repository, then on vercel.com choose **Add New › Project**, import the repository and set:

- **Root Directory:** `website` (leave it empty if this folder is the repository root)
- **Framework Preset:** Other
- **Build Command:** none · **Output Directory:** `.` (the default)

Every push then redeploys the site. Add your domain under **Settings › Domains**.

**From your computer.** With Node installed:

```sh
cd website
npx vercel        # preview deploy
npx vercel --prod # production
```

`vercel.json` turns on clean URLs, caches the media in `assets/` for a week and adds two security headers.

## What's in here

```text
website/
├── index.html     the page
├── styles.css     page styles and animations
├── app.js         the scroll story, phone screens, scene videos and voice stream
├── config.js      the values you change
├── favicon.svg    Tiny
├── vercel.json    Vercel settings
└── assets/
    ├── scene-0…7.mp4    the person's looping scenes (about 0.3–0.6 MB each)
    ├── scene-0…7.webp   still frames, shown before a video plays or with reduced motion
    └── og.png           link preview image
```

## Notes

- **The person** shows on screens 1320 px wide and up. Narrower screens show the text and the phone only.
- **Scene videos** have the page's cream colour (`#fffaf3`) built in, and their edges fade into the page. If you change the page background, re-colour the videos to match or the box behind the person will show.
- **Reduced motion:** visitors who set "reduce motion" get still frames and no idle animation.
- **Phone screen data** (calorie numbers, dates, names) is illustrative, not real user data.
