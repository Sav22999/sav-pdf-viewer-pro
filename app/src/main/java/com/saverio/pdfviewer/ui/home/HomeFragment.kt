package com.saverio.pdfviewer.ui.home

import android.content.Intent
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.saverio.pdfviewer.MainActivity
import com.saverio.pdfviewer.R
import com.saverio.pdfviewer.db.DatabaseHandler
import com.saverio.pdfviewer.db.FilesModel
import java.io.File

class HomeFragment : Fragment() {

    private lateinit var homeViewModel: HomeViewModel
    private val recentsRefreshHandler = Handler(Looper.getMainLooper())
    private val recentsRefreshIntervalMs = 1500L
    private val recentsRefreshRunnable = object : Runnable {
        override fun run() {
            view?.let { root ->
                renderRecents(root)
            }
            recentsRefreshHandler.postDelayed(this, recentsRefreshIntervalMs)
        }
    }

    private lateinit var recentsAdapter: RecentFilesAdapter
    private var isRecentSwipeActive = false
    private var hasPendingRecentsRefresh = false
    private val payoffHideHandler = Handler(Looper.getMainLooper())
    private val payoffHideDelayMs = 20_000L
    private val payoffHideDurationMs = 350L
    private var payoffText: TextView? = null
    private val payoffHideRunnable = Runnable {
        val payoff = payoffText ?: return@Runnable
        if (payoff.isGone) return@Runnable
        payoff.animate()
            .alpha(0f)
            .translationY(-payoff.resources.displayMetrics.density * 6f)
            .setDuration(payoffHideDurationMs)
            .withEndAction {
                payoff.isGone = true
            }
            .start()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        homeViewModel =
            ViewModelProvider(this).get(HomeViewModel::class.java)
        val root = inflater.inflate(R.layout.fragment_home, container, false)
        payoffText = root.findViewById(R.id.textHomePayoff)

        // Push the red brand bar's content below the status bar (edge-to-edge):
        // the bar itself keeps drawing behind the status bar, the text does not.
        val brandBar: View = root.findViewById(R.id.homeTopBrandBar)
        val brandBarBasePaddingTop = brandBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(brandBar) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.updatePadding(top = brandBarBasePaddingTop + topInset)
            insets
        }

        val main = activity as MainActivity
        val recentsList: RecyclerView = root.findViewById(R.id.recentsList)
        recentsAdapter = RecentFilesAdapter { file ->
            val openUri = storedPathToUri(file)
            if (openUri == null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.toast_recent_document_unreadable),
                    Toast.LENGTH_SHORT
                ).show()
                return@RecentFilesAdapter
            }
            if (openUri.scheme == "content") {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        openUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    // May fail without a current grant; PDFViewer will prompt for re-pick if needed.
                }
            }
            main.openPDFFile(openUri, openedExternally = false)
        }
        recentsList.layoutManager = LinearLayoutManager(requireContext())
        recentsList.adapter = recentsAdapter
        recentsList.setHasFixedSize(false)
        recentsList.isNestedScrollingEnabled = false

        val swipeCallback = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    recentsAdapter.notifyDataSetChanged()
                    return
                }

                val file = recentsAdapter.getItemAt(position)
                if (file == null) {
                    recentsAdapter.notifyItemChanged(position)
                    return
                }

                DatabaseHandler(requireContext()).deleteFile(file.id)
                recentsAdapter.removeItemAt(position)
                view?.let { currentView ->
                    val emptyText: TextView = currentView.findViewById(R.id.textNoRecents)
                    emptyText.isGone = recentsAdapter.itemCount > 0
                }
            }

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                isRecentSwipeActive = actionState == ItemTouchHelper.ACTION_STATE_SWIPE
                recentsList.parent?.requestDisallowInterceptTouchEvent(
                    actionState == ItemTouchHelper.ACTION_STATE_SWIPE
                )
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val holder = viewHolder as RecentFilesAdapter.RecentFileViewHolder
                val foregroundView = holder.card
                val backgroundView = holder.cardRemoved
                val activeSwipeColor = ContextCompat.getColor(requireContext(), R.color.dark_dark_red)
                val idleSwipeColor = ContextCompat.getColor(requireContext(), R.color.red)

                if (dX >= 0F) {
                    backgroundView.setCardBackgroundColor(idleSwipeColor)
                    ItemTouchHelper.Callback.getDefaultUIUtil().onDraw(
                        c,
                        recyclerView,
                        foregroundView,
                        0F,
                        dY,
                        actionState,
                        isCurrentlyActive
                    )
                    return
                }

                val maxSwipe = foregroundView.width * 0.90F
                val clampedDx = dX.coerceIn(-maxSwipe, 0F)
                val thresholdPx = foregroundView.width * getSwipeThreshold(viewHolder)
                val isDeleteActionArmed = kotlin.math.abs(clampedDx) >= thresholdPx
                backgroundView.setCardBackgroundColor(
                    if (isDeleteActionArmed) activeSwipeColor else idleSwipeColor
                )

                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    recyclerView.parent?.requestDisallowInterceptTouchEvent(
                        isCurrentlyActive || clampedDx != 0F
                    )
                }

                ItemTouchHelper.Callback.getDefaultUIUtil().onDraw(
                    c,
                    recyclerView,
                    foregroundView,
                    clampedDx,
                    dY,
                    actionState,
                    isCurrentlyActive
                )
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                val holder = viewHolder as RecentFilesAdapter.RecentFileViewHolder
                ItemTouchHelper.Callback.getDefaultUIUtil().clearView(holder.card)
                holder.cardRemoved.setCardBackgroundColor(
                    ContextCompat.getColor(requireContext(), R.color.red)
                )
                recyclerView.parent?.requestDisallowInterceptTouchEvent(false)
                isRecentSwipeActive = false
                if (hasPendingRecentsRefresh) {
                    hasPendingRecentsRefresh = false
                    view?.let { renderRecents(it) }
                }
                super.clearView(recyclerView, viewHolder)
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
                return 0.24F
            }

            override fun getSwipeEscapeVelocity(defaultValue: Float): Float {
                return defaultValue * 0.65F
            }

            override fun getSwipeVelocityThreshold(defaultValue: Float): Float {
                return defaultValue * 0.85F
            }
        }
        ItemTouchHelper(swipeCallback).attachToRecyclerView(recentsList)

        root.findViewById<View>(R.id.buttonHomeHelp).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.savpdfviewer.com/help/")))
        }

        renderRecents(root)
        resetAndSchedulePayoffHide()

        return root
    }

    override fun onResume() {
        super.onResume()
        recentsRefreshHandler.removeCallbacks(recentsRefreshRunnable)
        recentsRefreshRunnable.run()
        resetAndSchedulePayoffHide()
    }

    override fun onPause() {
        recentsRefreshHandler.removeCallbacks(recentsRefreshRunnable)
        payoffHideHandler.removeCallbacks(payoffHideRunnable)
        super.onPause()
    }

    override fun onDestroyView() {
        payoffHideHandler.removeCallbacks(payoffHideRunnable)
        payoffText = null
        super.onDestroyView()
    }

    private fun resetAndSchedulePayoffHide() {
        val payoff = payoffText ?: return
        payoffHideHandler.removeCallbacks(payoffHideRunnable)
        payoff.animate().cancel()
        payoff.alpha = 1f
        payoff.translationY = 0f
        payoff.isGone = false
        payoffHideHandler.postDelayed(payoffHideRunnable, payoffHideDelayMs)
    }

    private fun renderRecents(root: View) {
        val emptyText: TextView = root.findViewById(R.id.textNoRecents)

        if (isRecentSwipeActive) {
            hasPendingRecentsRefresh = true
            return
        }

        val database = DatabaseHandler(requireContext())
        val recentFiles = database
            .getFiles()
            .sortedByDescending { it.lastUpdate.ifBlank { it.date } }

        emptyText.isGone = recentFiles.isNotEmpty()
        recentsAdapter.submitList(recentFiles)
    }

    private fun storedPathToUri(file: FilesModel): Uri? {
        val path = file.path.ifBlank { return null }
        return if (path.contains("://")) {
            Uri.parse(path)
        } else {
            Uri.fromFile(File(path))
        }
    }

}