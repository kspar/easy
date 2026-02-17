# TODOs / Proposals

- **Course code as secondary text**: Add a `course_code` field to courses (DB + backend services) and display it as secondary text under the course title in the courses list. Currently alias overwrites the title; a dedicated code field would allow showing both.
- ~~**Persistent course color**: Save course accent color in DB so it remains consistent across title changes. Currently derived from title hash. Allow admin to set color when creating a course and change it in course settings.~~ ✅
