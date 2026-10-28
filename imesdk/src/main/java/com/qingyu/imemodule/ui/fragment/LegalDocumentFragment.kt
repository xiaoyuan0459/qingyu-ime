package com.qingyu.imemodule.ui.fragment

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.qingyu.imemodule.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class LegalDocumentFragment : Fragment(R.layout.fragment_legal_document) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rawRes = requireArguments().getInt(ARG_RAW)
        val assets = requireContext().assets
        viewLifecycleOwner.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                buildString {
                    append(resources.openRawResource(rawRes).bufferedReader().use { it.readText() })
                    if (rawRes == R.raw.third_party_notices) {
                        val directory = "licenses/native/"
                        val manifest = JSONArray(assets.open(directory + "manifest.json").bufferedReader().use { it.readText() })
                        for (index in 0 until manifest.length()) {
                            val entry = manifest.getJSONObject(index)
                            append("\n\n—— ${entry.getString("component")} / ${entry.getString("upstreamFile")} ——\n\n")
                            append(assets.open(directory + entry.getString("file")).bufferedReader().use { it.readText() })
                        }
                    }
                }
            }
            view.findViewById<TextView>(R.id.legal_text).text = text
        }
    }

    companion object {
        const val ARG_TITLE = "legalTitle"
        const val ARG_RAW = "rawRes"
    }
}
