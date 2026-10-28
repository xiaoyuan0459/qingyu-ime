
package com.qingyu.imemodule.ui.setup

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.qingyu.imemodule.databinding.FragmentSetupBinding
import com.qingyu.imemodule.prefs.AppPrefs
import com.qingyu.imemodule.ui.setup.SetupPage.Companion.isLastPage
import com.qingyu.imemodule.utils.serializable

class SetupFragment : Fragment() {

    private val viewModel: SetupViewModel by activityViewModels()

    private lateinit var binding: FragmentSetupBinding

    private val page: SetupPage by lazy { requireArguments().serializable(PAGE)!! }

    private var isDone: Boolean = false
        set(value) {
            if (value && page.isLastPage()) {
                viewModel.isAllDone.value = true
            }
            with(binding) {
                hintText.text = page.getHintText(requireContext())
                actionButton.visibility = if (value) View.GONE else View.VISIBLE
                actionButton.text = page.getButtonText(requireContext())
                actionButton.setOnClickListener { page.getButtonAction(requireContext()) }
                doneText.visibility = if (value) View.VISIBLE else View.GONE
                doneIcon.visibility = if (value) View.VISIBLE else View.GONE
            }
            field = value
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentSetupBinding.inflate(inflater)
        // 跳过只关掉「引导拦路」这一层，两步各自仍按真实状态判定，不会把配置标成已完成。
        // 从设置入口进来时 finish() 直接落回设置；从通知/桌面进来时就是退出引导。
        binding.skipButton.setOnClickListener {
            AppPrefs.getInstance().internal.setupSkipped.setValue(true)
            activity?.finish()
        }
        sync()
        return binding.root
    }

    // called on window focus changed
    fun sync() {
        isDone = page.isDone()
    }

    override fun onResume() {
        super.onResume()
        sync()
    }

    companion object {
        const val PAGE = "page"
    }

}