function ShowHide(sectionId, buttonLang) {
  otherLang = (buttonLang == "Java") ? "Kotlin" : "Java";

  var x = document.getElementById(sectionId + buttonLang);
  var h = document.getElementById(sectionId + otherLang);

  var xb = document.getElementById(sectionId + buttonLang + "Button");
  var hb = document.getElementById(sectionId + otherLang + "Button");

  xb_see_text = xb.innerHTML.includes("See");
  hb_see_text = hb.innerHTML.includes("See");

  if (x.style.display === "none") {
    x.style.display = "block";
    h.style.display = "none";
  } else {
    x.style.display = "none";
  }

  if (xb_see_text && hb_see_text) {
    xb.innerHTML = "Hide example (" + buttonLang + ")";
  }
  else if (xb_see_text && !hb_see_text) {
    xb.innerHTML = "Hide example (" + buttonLang + ")";
    hb.innerHTML = "See example (" + otherLang + ")";
  }
  else if (!xb_see_text && hb_see_text) {
    xb.innerHTML = "See example (" + buttonLang + ")";
  }
}